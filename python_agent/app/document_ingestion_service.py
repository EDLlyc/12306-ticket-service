from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
import hashlib
import json
from pathlib import Path
from typing import Any
import uuid

import httpx
from pymilvus import MilvusClient

from .document_ingestion import DocumentChunk, SUPPORTED_DOCUMENT_SUFFIXES, load_policy_document_chunks
from .llm_client import ZhipuLLMClient
from .logging_utils import get_logger, log_event
from .settings import Settings


@dataclass(frozen=True)
class IngestionOptions:
    source_path: str
    write_opensearch: bool = True
    write_milvus: bool = True
    force: bool = False
    dry_run: bool = False


@dataclass(frozen=True)
class IngestionResult:
    job_id: str
    source_path: str
    status: str
    file_hash: str
    chunk_count: int
    indexed_opensearch: int = 0
    indexed_milvus: int = 0
    skipped_reason: str | None = None
    errors: list[str] = field(default_factory=list)
    trace: dict[str, Any] = field(default_factory=dict)


class IngestionStateStore:
    def __init__(self, path: Path) -> None:
        self._path = path

    def load(self) -> dict[str, Any]:
        if not self._path.exists():
            return {"jobs": [], "documents": {}}
        try:
            return json.loads(self._path.read_text(encoding="utf-8"))
        except Exception:
            return {"jobs": [], "documents": {}}

    def save_result(self, result: IngestionResult) -> None:
        payload = self.load()
        payload.setdefault("jobs", []).append(asdict(result))
        documents = payload.setdefault("documents", {})
        if result.status == "success":
            documents[result.source_path] = {
                "fileHash": result.file_hash,
                "lastJobId": result.job_id,
                "lastStatus": result.status,
                "chunkCount": result.chunk_count,
                "updatedAt": utc_now(),
            }
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    def find_document_by_hash(self, source_path: str, file_hash: str) -> dict[str, Any] | None:
        document = self.load().get("documents", {}).get(source_path)
        if isinstance(document, dict) and document.get("fileHash") == file_hash:
            return document
        return None

    def recent_jobs(self, limit: int = 20) -> list[dict[str, Any]]:
        jobs = self.load().get("jobs", [])
        if not isinstance(jobs, list):
            return []
        return list(reversed(jobs[-limit:]))


class DocumentIngestionService:
    def __init__(
        self,
        settings: Settings,
        *,
        llm_client: ZhipuLLMClient | None = None,
        opensearch_http: httpx.Client | None = None,
        milvus_client: MilvusClient | None = None,
        state_store: IngestionStateStore | None = None,
    ) -> None:
        self._settings = settings
        self._llm_client = llm_client
        self._opensearch_http = opensearch_http
        self._milvus_client = milvus_client
        self._state_store = state_store or IngestionStateStore(Path(settings.ingestion_state_path))
        self._logger = get_logger("python_agent.document_ingestion")

    def ingest(self, options: IngestionOptions) -> IngestionResult:
        job_id = str(uuid.uuid4())
        source = Path(options.source_path)
        errors: list[str] = []
        trace: dict[str, Any] = {
            "jobId": job_id,
            "sourcePath": str(source),
            "startedAt": utc_now(),
            "dryRun": options.dry_run,
            "writeOpenSearchRequested": options.write_opensearch,
            "writeMilvusRequested": options.write_milvus,
        }

        file_hash = calculate_source_hash(source)
        if not source.exists():
            result = self._result(job_id, source, "failed", file_hash, 0, errors=["source_path_not_found"], trace=trace)
            self._state_store.save_result(result)
            return result

        existing = self._state_store.find_document_by_hash(str(source), file_hash)
        if existing and not options.force:
            result = self._result(
                job_id,
                source,
                "skipped",
                file_hash,
                int(existing.get("chunkCount") or 0),
                skipped_reason="same_hash_already_ingested",
                trace=dict(trace, existing=existing),
            )
            self._state_store.save_result(result)
            return result

        try:
            chunks = load_policy_document_chunks(source)
        except Exception as exc:
            result = self._result(job_id, source, "failed", file_hash, 0, errors=[str(exc)], trace=trace)
            self._state_store.save_result(result)
            return result

        trace["chunkCount"] = len(chunks)
        if options.dry_run:
            result = self._result(job_id, source, "dry_run", file_hash, len(chunks), trace=trace)
            self._state_store.save_result(result)
            return result

        indexed_opensearch = 0
        indexed_milvus = 0
        if options.write_opensearch and self._settings.opensearch_enabled:
            try:
                indexed_opensearch = self._index_opensearch(chunks)
                trace["opensearch"] = {
                    "enabled": True,
                    "index": self._settings.opensearch_index,
                    "indexed": indexed_opensearch,
                }
            except Exception as exc:
                errors.append(f"opensearch_index_failed: {exc}")
                trace["opensearch"] = {"enabled": True, "error": str(exc)}
        else:
            trace["opensearch"] = {
                "enabled": self._settings.opensearch_enabled,
                "indexed": 0,
                "reason": "disabled_or_not_requested",
            }

        if options.write_milvus and self._settings.milvus_enabled:
            try:
                indexed_milvus = self._index_milvus(chunks)
                trace["milvus"] = {
                    "enabled": True,
                    "collection": self._settings.milvus_collection_name,
                    "indexed": indexed_milvus,
                }
            except Exception as exc:
                errors.append(f"milvus_index_failed: {exc}")
                trace["milvus"] = {"enabled": True, "error": str(exc)}
        else:
            trace["milvus"] = {
                "enabled": self._settings.milvus_enabled,
                "indexed": 0,
                "reason": "disabled_or_not_requested",
            }

        status = "partial" if errors and (indexed_opensearch or indexed_milvus) else "failed" if errors else "success"
        result = self._result(
            job_id,
            source,
            status,
            file_hash,
            len(chunks),
            indexed_opensearch=indexed_opensearch,
            indexed_milvus=indexed_milvus,
            errors=errors,
            trace=dict(trace, finishedAt=utc_now()),
        )
        self._state_store.save_result(result)
        log_event(self._logger, "document_ingestion_finished", result=asdict(result))
        return result

    def recent_jobs(self, limit: int = 20) -> list[dict[str, Any]]:
        return self._state_store.recent_jobs(limit)

    def _index_opensearch(self, chunks: list[DocumentChunk]) -> int:
        if not chunks:
            return 0
        client = self._opensearch_http or httpx.Client(
            base_url=self._settings.opensearch_endpoint.rstrip("/"),
            timeout=10.0,
        )
        lines: list[str] = []
        for chunk in chunks:
            doc_id = stable_chunk_id(chunk)
            document = {
                "docId": doc_id,
                "parentId": doc_id,
                "text": f"{chunk.title}\n{chunk.content}",
                "parentText": chunk.content,
                "metadata": chunk.metadata,
            }
            lines.append(json.dumps({"index": {"_index": self._settings.opensearch_index, "_id": doc_id}}, ensure_ascii=False))
            lines.append(json.dumps(document, ensure_ascii=False))
        response = client.post("/_bulk", content=("\n".join(lines) + "\n").encode("utf-8"))
        response.raise_for_status()
        payload = response.json()
        if payload.get("errors"):
            raise RuntimeError("bulk_index_reported_errors")
        return len(chunks)

    def _index_milvus(self, chunks: list[DocumentChunk]) -> int:
        if not chunks:
            return 0
        if self._llm_client is None or not self._llm_client.cloud_enabled:
            raise RuntimeError("zhipu_embedding_client_unavailable")
        client = self._milvus_client or MilvusClient(uri=self._settings.milvus_uri)
        rows: list[dict[str, Any]] = []
        for chunk in chunks:
            embedding = self._llm_client.embed_text(build_embedding_text(chunk))
            if not embedding:
                raise RuntimeError(f"embedding_failed: {chunk.title[:40]}")
            rows.append(
                {
                    "id": stable_chunk_id(chunk),
                    "text": chunk.content,
                    "metadata": {
                        **chunk.metadata,
                        "parent_id": stable_chunk_id(chunk),
                        "parent_text": chunk.content,
                        "article_title": chunk.metadata.get("article_title") or chunk.title,
                    },
                    "vector": embedding,
                }
            )
        client.insert(collection_name=self._settings.milvus_collection_name, data=rows)
        return len(rows)

    @staticmethod
    def _result(
        job_id: str,
        source: Path,
        status: str,
        file_hash: str,
        chunk_count: int,
        *,
        indexed_opensearch: int = 0,
        indexed_milvus: int = 0,
        skipped_reason: str | None = None,
        errors: list[str] | None = None,
        trace: dict[str, Any] | None = None,
    ) -> IngestionResult:
        return IngestionResult(
            job_id=job_id,
            source_path=str(source),
            status=status,
            file_hash=file_hash,
            chunk_count=chunk_count,
            indexed_opensearch=indexed_opensearch,
            indexed_milvus=indexed_milvus,
            skipped_reason=skipped_reason,
            errors=errors or [],
            trace=trace or {},
        )


def calculate_source_hash(path: Path) -> str:
    digest = hashlib.sha256()
    if not path.exists():
        return ""
    if path.is_file():
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
        return digest.hexdigest()
    for candidate in sorted(
        item
        for item in path.rglob("*")
        if item.is_file() and item.suffix.lower() in SUPPORTED_DOCUMENT_SUFFIXES
    ):
        digest.update(str(candidate.relative_to(path)).encode("utf-8"))
        digest.update(candidate.read_bytes())
    return digest.hexdigest()


def stable_chunk_id(chunk: DocumentChunk) -> str:
    raw = f"{chunk.chunk_id}\n{chunk.title}\n{chunk.content}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()


def build_embedding_text(chunk: DocumentChunk) -> str:
    return f"【{chunk.title}】\n{chunk.content}"[:4096]


def utc_now() -> str:
    return datetime.now(UTC).isoformat()
