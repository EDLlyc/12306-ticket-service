from typing import Any

from pydantic import BaseModel, Field


class AskRequest(BaseModel):
    session_id: str = Field(..., min_length=1)
    question: str = Field(..., min_length=1)
    username: str | None = None
    token: str | None = None


class AskResponse(BaseModel):
    session_id: str
    answer: str
    mode: str = "python_agent"
    tool_result: dict[str, Any] | None = None


class EvalAskResponse(BaseModel):
    answer: str
    contexts: list[str] = Field(default_factory=list)
    trace: dict[str, Any] | None = None


class StreamEvent(BaseModel):
    event: str
    data: dict[str, Any] | str


class JavaResult(BaseModel):
    code: int
    message: str
    data: Any = None


class DebugClearCacheRequest(BaseModel):
    question: str | None = None


class DebugSessionResponse(BaseModel):
    session_id: str
    turns: list[dict[str, Any]]
    summary: str | None = None


class DebugCacheClearResponse(BaseModel):
    cleared: int
    question: str | None = None


class OllamaChatRequest(BaseModel):
    message: str = Field(..., min_length=1)
    system_prompt: str | None = None
    session_id: str | None = None


class OllamaChatResponse(BaseModel):
    answer: str
    model: str


class OllamaSwitchModelRequest(BaseModel):
    model: str = Field(..., min_length=1)


class DocumentIngestRequest(BaseModel):
    source_path: str = Field(..., min_length=1)
    write_opensearch: bool = True
    write_milvus: bool = True
    force: bool = False
    dry_run: bool = False


class DocumentIngestResponse(BaseModel):
    job_id: str
    source_path: str
    status: str
    file_hash: str
    chunk_count: int
    indexed_opensearch: int = 0
    indexed_milvus: int = 0
    skipped_reason: str | None = None
    errors: list[str] = Field(default_factory=list)
    trace: dict[str, Any] = Field(default_factory=dict)
