import tempfile
import unittest
import zipfile
from pathlib import Path

from python_agent.app.document_ingestion import load_policy_document_chunks, load_source_documents
from python_agent.app.document_ingestion_service import DocumentIngestionService, IngestionOptions
from python_agent.app.hybrid_retriever import HybridRetriever
from python_agent.app.settings import Settings


DOCX_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>
"""

DOCX_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
"""


class DocumentIngestionTest(unittest.TestCase):
    def test_loads_markdown_and_txt_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "rules.md").write_text(
                "# 铁路旅客运输规程\n\n"
                "## 售票条件\n"
                "第十六条 学生优惠票\n学生旅客每学年可购买优惠票。\n\n"
                "第十七条 儿童优惠票\n儿童旅客按年龄购买优惠票。\n",
                encoding="utf-8",
            )
            (root / "refund.txt").write_text(
                "第九十九条 退票\n开车前可以按规定办理退票。",
                encoding="utf-8",
            )

            chunks = load_policy_document_chunks(root)

        titles = [chunk.title for chunk in chunks]
        self.assertTrue(any("学生优惠票" in title for title in titles))
        self.assertTrue(any("儿童优惠票" in title for title in titles))
        self.assertTrue(any("退票" in title for title in titles))
        self.assertTrue(all(chunk.metadata["source_path"] for chunk in chunks))

    def test_docx_uses_stdlib_fallback(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            docx_path = Path(temp_dir) / "policy.docx"
            self._write_minimal_docx(
                docx_path,
                [
                    "铁路旅客运输规程",
                    "第十八条 报销凭证",
                    "报销凭证可在规定期限内开具。",
                ],
            )

            documents = load_source_documents(docx_path)

        self.assertEqual(len(documents), 1)
        self.assertIn("报销凭证", documents[0].text)
        self.assertEqual(documents[0].metadata["format"], "docx")

    def test_hybrid_retriever_can_use_document_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "student.md").write_text(
                "# 铁路旅客运输规程\n\n"
                "## 售票条件\n"
                "第十六条 学生优惠票\n学生旅客需完成资质核验并在优惠区间内乘车。",
                encoding="utf-8",
            )
            settings = Settings(policy_rules_path=str(root))
            retriever = HybridRetriever(settings)
            result = retriever.retrieve("学生票 资质核验")

        self.assertTrue(result.chunks)
        self.assertIn("学生优惠票", result.chunks[0].title)

    def test_ingestion_service_dry_run_does_not_mark_document_ingested(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "student.md"
            state_path = root / "jobs.json"
            source.write_text(
                "# 铁路旅客运输规程\n\n"
                "## 售票条件\n"
                "第十六条 学生优惠票\n学生旅客需完成资质核验。",
                encoding="utf-8",
            )
            service = DocumentIngestionService(
                Settings(opensearch_enabled=False, milvus_enabled=False, ingestion_state_path=str(state_path))
            )

            first = service.ingest(IngestionOptions(source_path=str(source), dry_run=True))
            second = service.ingest(IngestionOptions(source_path=str(source), dry_run=True))

        self.assertEqual(first.status, "dry_run")
        self.assertEqual(first.chunk_count, 1)
        self.assertEqual(second.status, "dry_run")

    def test_ingestion_service_skips_same_hash_after_success(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "student.md"
            state_path = root / "jobs.json"
            source.write_text(
                "# 铁路旅客运输规程\n\n"
                "## 售票条件\n"
                "第十六条 学生优惠票\n学生旅客需完成资质核验。",
                encoding="utf-8",
            )
            service = DocumentIngestionService(
                Settings(opensearch_enabled=False, milvus_enabled=False, ingestion_state_path=str(state_path))
            )

            first = service.ingest(IngestionOptions(source_path=str(source)))
            second = service.ingest(IngestionOptions(source_path=str(source)))

        self.assertEqual(first.status, "success")
        self.assertEqual(second.status, "skipped")
        self.assertEqual(second.skipped_reason, "same_hash_already_ingested")

    def test_ingestion_service_indexes_opensearch(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "refund.md"
            state_path = root / "jobs.json"
            source.write_text(
                "# 铁路旅客运输规程\n\n"
                "## 退票规则\n"
                "第四十五条 退票\n开车前可以按规定办理退票。",
                encoding="utf-8",
            )
            opensearch = FakeBulkOpenSearchClient()
            service = DocumentIngestionService(
                Settings(opensearch_enabled=True, milvus_enabled=False, ingestion_state_path=str(state_path)),
                opensearch_http=opensearch,
            )

            result = service.ingest(IngestionOptions(source_path=str(source), write_milvus=False))

        self.assertEqual(result.status, "success")
        self.assertEqual(result.indexed_opensearch, 1)
        self.assertIn("/_bulk", opensearch.paths)
        self.assertIn("退票", opensearch.bulk_body)

    def test_ingestion_service_indexes_milvus_with_embeddings(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "student.md"
            state_path = root / "jobs.json"
            source.write_text(
                "# 铁路旅客运输规程\n\n"
                "## 售票条件\n"
                "第十六条 学生优惠票\n学生旅客需完成资质核验。",
                encoding="utf-8",
            )
            milvus = FakeMilvusInsertClient()
            service = DocumentIngestionService(
                Settings(milvus_enabled=True, opensearch_enabled=False, ingestion_state_path=str(state_path)),
                llm_client=FakeEmbeddingLLMClient(),
                milvus_client=milvus,
            )

            result = service.ingest(IngestionOptions(source_path=str(source), write_opensearch=False))

        self.assertEqual(result.status, "success")
        self.assertEqual(result.indexed_milvus, 1)
        self.assertEqual(milvus.collection_name, "rules_embedding3_1024")
        self.assertEqual(len(milvus.rows), 1)
        self.assertIn("vector", milvus.rows[0])

    @staticmethod
    def _write_minimal_docx(path: Path, paragraphs: list[str]) -> None:
        body = "".join(
            f"<w:p><w:r><w:t>{paragraph}</w:t></w:r></w:p>"
            for paragraph in paragraphs
        )
        document_xml = (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            f"<w:body>{body}</w:body>"
            "</w:document>"
        )
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("[Content_Types].xml", DOCX_CONTENT_TYPES)
            archive.writestr("_rels/.rels", DOCX_RELS)
            archive.writestr("word/document.xml", document_xml)


class FakeBulkResponse:
    status_code = 200

    def raise_for_status(self):
        return None

    def json(self):
        return {"errors": False}


class FakeBulkOpenSearchClient:
    def __init__(self):
        self.paths = []
        self.bulk_body = ""

    def post(self, path, content):
        self.paths.append(path)
        self.bulk_body = content.decode("utf-8") if isinstance(content, bytes) else str(content)
        return FakeBulkResponse()


class FakeMilvusInsertClient:
    def __init__(self):
        self.collection_name = ""
        self.rows = []

    def insert(self, *, collection_name, data):
        self.collection_name = collection_name
        self.rows = data


class FakeEmbeddingLLMClient:
    cloud_enabled = True

    def embed_text(self, text):
        return [0.1] * 1024


if __name__ == "__main__":
    unittest.main()
