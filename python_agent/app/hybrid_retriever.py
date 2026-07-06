from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
from typing import Any

import httpx
from pymilvus import MilvusClient

from .document_ingestion import load_policy_document_chunks
from .llm_client import ZhipuLLMClient
from .logging_utils import get_logger, log_event
from .settings import Settings


SECTION_SPLIT_PATTERN = re.compile(r"(?m)^##?\s+")
ARTICLE_SPLIT_PATTERN = re.compile(r"(?m)^第[一二三四五六七八九十百千零〇\d]+条\s+")


@dataclass(frozen=True)
class ChunkRecord:
    chunk_id: str
    title: str
    content: str


@dataclass(frozen=True)
class RetrievedChunk:
    title: str
    content: str
    dense_score: float
    sparse_score: float
    fused_score: float
    rerank_score: float = 0.0
    chunk_key: str = ""


@dataclass(frozen=True)
class RetrievalResult:
    chunks: list[RetrievedChunk]
    trace: dict


class HybridRetriever:
    def __init__(
        self,
        settings: Settings,
        *,
        milvus_client: MilvusClient | None = None,
        opensearch_http: httpx.Client | None = None,
        llm_client: ZhipuLLMClient | None = None,
    ) -> None:
        self._settings = settings
        self._top_k = settings.policy_top_k
        self._chunks = self._load_chunks(Path(settings.policy_rules_path))
        self._milvus_client = milvus_client if milvus_client is not None else self._build_milvus_client()
        self._opensearch_http = opensearch_http if opensearch_http is not None else self._build_opensearch_http_client()
        self._llm_client = llm_client
        self._logger = get_logger("python_agent.hybrid_retriever")

    def retrieve(self, question: str) -> RetrievalResult:
        dense_hits, dense_trace = self._dense_retrieve(question)
        sparse_hits, sparse_trace = self._sparse_retrieve(question)
        fused = self._rrf_fuse(dense_hits, sparse_hits)
        reranked, rerank_trace = self._rerank(question, fused)
        selected = reranked[: self._top_k]
        trace = {
            "retriever": "hybrid_rrf",
            "dense": dense_trace,
            "sparse": sparse_trace,
            "rrfK": self._settings.rrf_k,
            "rerankEnabled": self._settings.rerank_enabled,
            "rerank": rerank_trace,
            "selectedCount": len(selected),
            "matchCount": len(reranked),
            "fallbackMode": self._fallback_mode(dense_trace, sparse_trace),
        }
        log_event(
            self._logger,
            "hybrid_retrieve",
            question=question,
            dense=dense_trace,
            sparse=sparse_trace,
            rerank=rerank_trace,
            selectedCount=len(selected),
            fallbackMode=trace["fallbackMode"],
        )
        return RetrievalResult(chunks=selected, trace=trace)

    def _dense_retrieve(self, question: str) -> tuple[list[RetrievedChunk], dict]:
        healthy = self._milvus_healthy() if self._milvus_client is not None else False
        if healthy:
            embedding, embedding_trace = self._embed_query(question)
            if embedding is not None:
                try:
                    rows = self._milvus_client.search(  # type: ignore[union-attr]
                        collection_name=self._settings.milvus_collection_name,
                        data=[embedding],
                        limit=max(self._top_k * 4, 8),
                        output_fields=["id", "text", "metadata"],
                        anns_field="vector",
                    )
                    hits = self._parse_milvus_results(rows)
                    return hits, {
                        "backend": "milvus",
                        "enabled": self._settings.milvus_enabled,
                        "healthy": True,
                        "source": "remote",
                        "matchCount": len(hits),
                        "collection": self._settings.milvus_collection_name,
                        "embedding": embedding_trace,
                    }
                except Exception as exc:
                    return self._dense_retrieve_local(
                        question,
                        healthy=True,
                        reason="remote_search_failed",
                        error=str(exc),
                        embedding_trace=embedding_trace,
                    )
            return self._dense_retrieve_local(
                question,
                healthy=True,
                reason="embedding_unavailable",
                embedding_trace=embedding_trace,
            )
        return self._dense_retrieve_local(
            question,
            healthy=False,
            reason="backend_unavailable" if self._settings.milvus_enabled else "disabled",
        )

    def _dense_retrieve_local(
        self,
        question: str,
        *,
        healthy: bool,
        reason: str,
        error: str | None = None,
        embedding_trace: dict | None = None,
    ) -> tuple[list[RetrievedChunk], dict]:
        terms = extract_terms(question)
        hits: list[RetrievedChunk] = []
        for chunk in self._chunks:
            dense_score = overlap_score(terms, chunk.title, chunk.content)
            if dense_score > 0:
                hits.append(
                    RetrievedChunk(
                        title=chunk.title,
                        content=chunk.content,
                        dense_score=dense_score,
                        sparse_score=0.0,
                        fused_score=0.0,
                        chunk_key=chunk.chunk_id,
                    )
                )
        hits.sort(key=lambda item: item.dense_score, reverse=True)
        trace = {
            "backend": "milvus" if healthy else "local_dense",
            "enabled": self._settings.milvus_enabled,
            "healthy": healthy,
            "source": "local_fallback",
            "matchCount": len(hits),
            "reason": reason,
        }
        if embedding_trace is not None:
            trace["embedding"] = embedding_trace
        if error:
            trace["error"] = error
        return hits, trace

    def _sparse_retrieve(self, question: str) -> tuple[list[RetrievedChunk], dict]:
        healthy = self._opensearch_healthy() if self._opensearch_http is not None else False
        if healthy:
            try:
                payload = {
                    "size": max(self._top_k * 4, 8),
                    "_source": ["docId", "text", "parentId", "parentText"],
                    "query": {
                        "bool": {
                            "should": [
                                {
                                    "multi_match": {
                                        "query": question,
                                        "fields": ["text^3", "parentText"],
                                        "type": "best_fields",
                                    }
                                },
                                {
                                    "match_phrase": {
                                        "text": {
                                            "query": question,
                                            "boost": 2.5,
                                        }
                                    }
                                },
                            ],
                            "minimum_should_match": 1,
                        }
                    },
                }
                response = self._opensearch_http.post(f"/{self._settings.opensearch_index}/_search", json=payload)  # type: ignore[union-attr]
                if response.status_code == 200:
                    hits = self._parse_opensearch_results(response.json())
                    return hits, {
                        "backend": "opensearch",
                        "enabled": self._settings.opensearch_enabled,
                        "healthy": True,
                        "source": "remote",
                        "matchCount": len(hits),
                        "index": self._settings.opensearch_index,
                    }
                return self._sparse_retrieve_local(
                    question,
                    healthy=True,
                    reason="remote_search_failed",
                    error=f"status={response.status_code}",
                )
            except Exception as exc:
                return self._sparse_retrieve_local(
                    question,
                    healthy=True,
                    reason="remote_search_failed",
                    error=str(exc),
                )
        return self._sparse_retrieve_local(
            question,
            healthy=False,
            reason="backend_unavailable" if self._settings.opensearch_enabled else "disabled",
        )

    def _sparse_retrieve_local(
        self,
        question: str,
        *,
        healthy: bool,
        reason: str,
        error: str | None = None,
    ) -> tuple[list[RetrievedChunk], dict]:
        terms = extract_terms(question)
        hits: list[RetrievedChunk] = []
        for chunk in self._chunks:
            sparse_score = bm25_like_score(terms, chunk.title, chunk.content)
            if sparse_score > 0:
                hits.append(
                    RetrievedChunk(
                        title=chunk.title,
                        content=chunk.content,
                        dense_score=0.0,
                        sparse_score=sparse_score,
                        fused_score=0.0,
                        chunk_key=chunk.chunk_id,
                    )
                )
        hits.sort(key=lambda item: item.sparse_score, reverse=True)
        trace = {
            "backend": "opensearch" if healthy else "local_sparse",
            "enabled": self._settings.opensearch_enabled,
            "healthy": healthy,
            "source": "local_fallback",
            "matchCount": len(hits),
            "reason": reason,
        }
        if error:
            trace["error"] = error
        return hits, trace

    def _rrf_fuse(self, dense_hits: list[RetrievedChunk], sparse_hits: list[RetrievedChunk]) -> list[RetrievedChunk]:
        dense_rank = {self._chunk_identity(chunk): index for index, chunk in enumerate(dense_hits, start=1)}
        sparse_rank = {self._chunk_identity(chunk): index for index, chunk in enumerate(sparse_hits, start=1)}
        merged: dict[str, RetrievedChunk] = {}

        for chunk in dense_hits + sparse_hits:
            identity = self._chunk_identity(chunk)
            existing = merged.get(identity)
            dense_score = max(chunk.dense_score, existing.dense_score if existing else 0.0)
            sparse_score = max(chunk.sparse_score, existing.sparse_score if existing else 0.0)
            rank_score = 0.0
            if identity in dense_rank:
                rank_score += 1.0 / (self._settings.rrf_k + dense_rank[identity])
            if identity in sparse_rank:
                rank_score += 1.0 / (self._settings.rrf_k + sparse_rank[identity])
            preferred = self._preferred_chunk(existing, chunk)
            merged[identity] = RetrievedChunk(
                title=preferred.title,
                content=preferred.content,
                dense_score=dense_score,
                sparse_score=sparse_score,
                fused_score=rank_score,
                chunk_key=identity,
            )
        return sorted(merged.values(), key=lambda item: item.fused_score, reverse=True)

    def _rerank(self, question: str, chunks: list[RetrievedChunk]) -> tuple[list[RetrievedChunk], dict]:
        if not chunks:
            return [], {"enabled": self._settings.rerank_enabled, "source": "disabled", "candidateCount": 0}
        if not self._settings.rerank_enabled:
            return chunks, {"enabled": False, "source": "disabled", "candidateCount": len(chunks)}
        if self._llm_client is not None and self._llm_client.enabled and len(chunks) > 1:
            remote = self._rerank_remote(question, chunks)
            if remote is not None:
                return remote, {
                    "enabled": True,
                    "source": "remote",
                    "candidateCount": len(chunks),
                    "selectedCount": len(remote),
                    "model": self._settings.zhipu_rerank_model,
                }
        terms = set(extract_terms(question))
        reranked: list[RetrievedChunk] = []
        for chunk in chunks:
            overlap = len({term for term in terms if term in f"{chunk.title}\n{chunk.content}"})
            reranked.append(
                RetrievedChunk(
                    title=chunk.title,
                    content=chunk.content,
                    dense_score=chunk.dense_score,
                    sparse_score=chunk.sparse_score,
                    fused_score=chunk.fused_score,
                    rerank_score=float(overlap),
                    chunk_key=chunk.chunk_key,
                )
            )
        reranked.sort(key=lambda item: (item.rerank_score, item.fused_score), reverse=True)
        return reranked, {
            "enabled": True,
            "source": "local_heuristic",
            "candidateCount": len(chunks),
            "selectedCount": len(reranked),
        }

    @staticmethod
    def _load_chunks(path: Path) -> list[ChunkRecord]:
        return [
            ChunkRecord(chunk_id=chunk.chunk_id, title=chunk.title, content=chunk.content)
            for chunk in load_policy_document_chunks(path)
        ]

    def _build_milvus_client(self) -> MilvusClient | None:
        if not self._settings.milvus_enabled:
            return None
        try:
            return MilvusClient(uri=self._settings.milvus_uri)
        except Exception:
            return None

    def _build_opensearch_http_client(self) -> httpx.Client | None:
        if not self._settings.opensearch_enabled:
            return None
        try:
            return httpx.Client(base_url=self._settings.opensearch_endpoint.rstrip("/"), timeout=2.0)
        except Exception:
            return None

    def _milvus_healthy(self) -> bool:
        try:
            assert self._milvus_client is not None
            collections = self._milvus_client.list_collections()
            return self._settings.milvus_collection_name in collections
        except Exception:
            return False

    def _opensearch_healthy(self) -> bool:
        try:
            assert self._opensearch_http is not None
            response = self._opensearch_http.get("/_cluster/health")
            return response.status_code == 200
        except Exception:
            return False

    def _embed_query(self, question: str) -> tuple[list[float] | None, dict]:
        if not self._settings.zhipu_api_key:
            return None, {"provider": "zhipu", "enabled": False, "reason": "missing_api_key"}
        try:
            from zhipuai import ZhipuAI

            client = ZhipuAI(api_key=self._settings.zhipu_api_key)
            response = client.embeddings.create(
                input=question,
                model=self._settings.zhipu_embedding_model,
                dimensions=self._settings.zhipu_embedding_dimensions,
            )
            data = getattr(response, "data", None) or []
            if not data:
                return None, {"provider": "zhipu", "enabled": True, "reason": "empty_embedding"}
            vector = getattr(data[0], "embedding", None)
            if not vector:
                return None, {"provider": "zhipu", "enabled": True, "reason": "empty_embedding"}
            return list(vector), {
                "provider": "zhipu",
                "enabled": True,
                "model": self._settings.zhipu_embedding_model,
                "dimensions": self._settings.zhipu_embedding_dimensions,
            }
        except Exception as exc:
            return None, {
                "provider": "zhipu",
                "enabled": True,
                "reason": "embedding_request_failed",
                "error": str(exc),
            }

    def _rerank_remote(self, question: str, chunks: list[RetrievedChunk]) -> list[RetrievedChunk] | None:
        if self._llm_client is None:
            return None
        documents = [build_rerank_document(chunk) for chunk in chunks]
        try:
            indexes = self._llm_client.rerank_documents(
                query=question,
                documents=documents,
                top_n=min(self._top_k, len(documents)),
            )
            if not indexes:
                return None
            reranked: list[RetrievedChunk] = []
            selected: set[int] = set()
            for rank, index in enumerate(indexes, start=1):
                if index < 0 or index >= len(chunks) or index in selected:
                    continue
                selected.add(index)
                chunk = chunks[index]
                reranked.append(
                    RetrievedChunk(
                        title=chunk.title,
                        content=chunk.content,
                        dense_score=chunk.dense_score,
                        sparse_score=chunk.sparse_score,
                        fused_score=chunk.fused_score,
                        rerank_score=float(len(indexes) - rank + 1),
                        chunk_key=chunk.chunk_key,
                    )
                )
            for index, chunk in enumerate(chunks):
                if index in selected:
                    continue
                reranked.append(
                    RetrievedChunk(
                        title=chunk.title,
                        content=chunk.content,
                        dense_score=chunk.dense_score,
                        sparse_score=chunk.sparse_score,
                        fused_score=chunk.fused_score,
                        rerank_score=0.0,
                        chunk_key=chunk.chunk_key,
                    )
                )
            return reranked
        except Exception:
            return None

    def _parse_opensearch_results(self, payload: dict[str, Any]) -> list[RetrievedChunk]:
        hits = payload.get("hits", {}).get("hits", [])
        results: list[RetrievedChunk] = []
        for hit in hits:
            source = hit.get("_source", {}) or {}
            text = str(source.get("text") or "").strip()
            parent_text = str(source.get("parentText") or "").strip()
            content = parent_text or text
            if not content:
                continue
            title = derive_title(text or parent_text)
            chunk_key = str(source.get("parentId") or source.get("docId") or title)
            results.append(
                RetrievedChunk(
                    title=title,
                    content=content,
                    dense_score=0.0,
                    sparse_score=float(hit.get("_score") or 0.0),
                    fused_score=0.0,
                    chunk_key=chunk_key,
                )
            )
        return results

    def _parse_milvus_results(self, rows: Any) -> list[RetrievedChunk]:
        groups = rows or []
        if not groups:
            return []
        results: list[RetrievedChunk] = []
        for row in groups[0]:
            entity = row.get("entity", {}) or {}
            metadata = entity.get("metadata", {}) or {}
            text = str(entity.get("text") or "").strip()
            parent_text = str(metadata.get("parent_text") or "").strip()
            content = parent_text or text
            if not content:
                continue
            title = build_milvus_title(metadata, text or parent_text)
            distance = float(row.get("distance") or 0.0)
            dense_score = 1.0 / (1.0 + max(distance, 0.0))
            chunk_key = str(metadata.get("parent_id") or entity.get("id") or title)
            results.append(
                RetrievedChunk(
                    title=title,
                    content=content,
                    dense_score=dense_score,
                    sparse_score=0.0,
                    fused_score=0.0,
                    chunk_key=chunk_key,
                )
            )
        return results

    @staticmethod
    def _chunk_identity(chunk: RetrievedChunk) -> str:
        return chunk.chunk_key or chunk.title

    @staticmethod
    def _preferred_chunk(existing: RetrievedChunk | None, current: RetrievedChunk) -> RetrievedChunk:
        if existing is None:
            return current
        if len(current.content) > len(existing.content):
            return current
        return existing

    @staticmethod
    def _fallback_mode(dense_trace: dict[str, Any], sparse_trace: dict[str, Any]) -> str:
        dense_source = "remote_dense" if dense_trace.get("source") == "remote" else "local_dense"
        sparse_source = "remote_sparse" if sparse_trace.get("source") == "remote" else "local_sparse"
        return f"{dense_source}_{sparse_source}"


def split_section(title: str, content: str) -> list[tuple[str, str]]:
    article_matches = list(ARTICLE_SPLIT_PATTERN.finditer(content))
    if not article_matches:
        return [(title, content)]
    chunks: list[tuple[str, str]] = []
    for index, match in enumerate(article_matches):
        start = match.start()
        end = article_matches[index + 1].start() if index + 1 < len(article_matches) else len(content)
        article = content[start:end].strip()
        first_line_end = article.find("\n")
        article_title = article[:first_line_end].strip() if first_line_end != -1 else article[:32].strip()
        chunks.append((f"{title} / {article_title}", article))
    return chunks


def extract_terms(question: str) -> list[str]:
    raw_terms = re.split(r"[\s，。；、：,:？?！!（）()]+", question.strip())
    return [term for term in raw_terms if len(term) >= 2]


def bm25_like_score(terms: list[str], title: str, content: str) -> float:
    score = 0.0
    haystack = f"{title}\n{content}"
    for term in terms:
        if term in title:
            score += 3.0
        elif term in haystack:
            score += 1.5
    return score


def overlap_score(terms: list[str], title: str, content: str) -> float:
    haystack = f"{title}\n{content}"
    unique_hits = len({term for term in terms if term in haystack})
    return unique_hits * 0.7


def derive_title(text: str) -> str:
    lines = [line.strip(" #\r") for line in text.splitlines() if line.strip()]
    if not lines:
        return "铁路规则"
    return lines[0][:80]


def build_milvus_title(metadata: dict[str, Any], text: str) -> str:
    parts = [
        str(metadata.get("chapter") or "").strip(),
        str(metadata.get("section") or "").strip(),
        str(metadata.get("article_title") or "").strip(),
    ]
    normalized = [part for part in parts if part]
    if normalized:
        return " / ".join(normalized)
    return derive_title(text)


def build_rerank_document(chunk: RetrievedChunk) -> str:
    return f"【{chunk.title}】\n{chunk.content}"
