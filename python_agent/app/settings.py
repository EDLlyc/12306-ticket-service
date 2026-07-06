from functools import lru_cache
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=("python_agent/.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        populate_by_name=True,
    )

    host: str = Field(default="0.0.0.0", alias="PYTHON_AGENT_HOST")
    port: int = Field(default=8898, alias="PYTHON_AGENT_PORT")
    java_ticket_base_url: str = Field(
        default="http://127.0.0.1:8899",
        alias="JAVA_TICKET_BASE_URL",
    )
    java_ticket_token_header: str = Field(
        default="X-Login-Token",
        alias="JAVA_TICKET_TOKEN_HEADER",
    )
    response_chunk_size: int = Field(default=24, alias="AGENT_RESPONSE_CHUNK_SIZE")
    zhipu_api_key: str | None = Field(default=None, alias="ZHIPU_API_KEY")
    zhipu_agent_model: str = Field(default="glm-4.5-air", alias="ZHIPU_AGENT_MODEL")
    zhipu_policy_model: str = Field(default="glm-5.1", alias="ZHIPU_POLICY_MODEL")
    zhipu_policy_fast_model: str = Field(default="glm-4.5-air", alias="ZHIPU_POLICY_FAST_MODEL")
    ollama_enabled: bool = Field(default=False, alias="OLLAMA_ENABLED")
    ollama_base_url: str = Field(default="http://127.0.0.1:11434", alias="OLLAMA_BASE_URL")
    ollama_aux_model: str = Field(default="qwen2.5:7b", alias="OLLAMA_AUX_MODEL")
    ollama_connect_timeout_ms: int = Field(default=3000, alias="OLLAMA_CONNECT_TIMEOUT_MS")
    ollama_read_timeout_ms: int = Field(default=20000, alias="OLLAMA_READ_TIMEOUT_MS")
    ollama_chat_max_tokens: int = Field(default=256, alias="OLLAMA_CHAT_MAX_TOKENS")
    zhipu_embedding_model: str = Field(default="embedding-3", alias="ZHIPU_EMBEDDING_MODEL")
    zhipu_embedding_dimensions: int = Field(default=1024, alias="ZHIPU_EMBEDDING_DIMENSIONS")
    zhipu_rerank_model: str = Field(default="rerank", alias="ZHIPU_RERANK_MODEL")
    policy_rules_path: str = Field(default="ragas_eval/12306_rules.txt", alias="PYTHON_AGENT_POLICY_RULES_PATH")
    policy_top_k: int = Field(default=4, alias="PYTHON_AGENT_POLICY_TOP_K")
    planned_rag_enabled: bool = Field(default=True, alias="PYTHON_AGENT_PLANNED_RAG_ENABLED")
    planned_rag_max_subqueries: int = Field(default=3, alias="PYTHON_AGENT_PLANNED_RAG_MAX_SUBQUERIES")
    session_memory_limit: int = Field(default=12, alias="PYTHON_AGENT_SESSION_MEMORY_LIMIT")
    semantic_cache_limit: int = Field(default=128, alias="PYTHON_AGENT_SEMANTIC_CACHE_LIMIT")
    intent_high_confidence: float = Field(default=0.75, alias="PYTHON_AGENT_INTENT_HIGH_CONFIDENCE")
    intent_margin_threshold: float = Field(default=0.12, alias="PYTHON_AGENT_INTENT_MARGIN_THRESHOLD")
    redis_host: str = Field(default="127.0.0.1", alias="REDIS_HOST")
    redis_port: int = Field(default=26379, alias="REDIS_PORT")
    redis_db: int = Field(default=0, alias="REDIS_DB")
    milvus_enabled: bool = Field(default=False, alias="PYTHON_AGENT_MILVUS_ENABLED")
    milvus_uri: str = Field(default="http://127.0.0.1:19530", alias="MILVUS_URI")
    milvus_collection_name: str = Field(default="rules_embedding3_1024", alias="MILVUS_COLLECTION_NAME")
    opensearch_enabled: bool = Field(default=False, alias="PYTHON_AGENT_OPENSEARCH_ENABLED")
    opensearch_endpoint: str = Field(default="http://127.0.0.1:29200", alias="OPENSEARCH_ENDPOINT")
    opensearch_index: str = Field(default="ticket_rules_sparse", alias="OPENSEARCH_INDEX")
    rerank_enabled: bool = Field(default=False, alias="PYTHON_AGENT_RERANK_ENABLED")
    rrf_k: int = Field(default=60, alias="PYTHON_AGENT_RRF_K")
    ingestion_state_path: str = Field(default="python_agent/.ingestion/jobs.json", alias="PYTHON_AGENT_INGESTION_STATE_PATH")
    weather_mcp_endpoint: str | None = Field(default=None, alias="WEATHER_MCP_ENDPOINT")
    weather_mcp_tool_name: str = Field(default="get_weather_by_city", alias="WEATHER_MCP_TOOL_NAME")
    weather_mcp_timeout_ms: int = Field(default=3000, alias="WEATHER_MCP_TIMEOUT_MS")
    weather_mcp_host: str = Field(default="127.0.0.1", alias="WEATHER_MCP_HOST")
    weather_mcp_port: int = Field(default=8897, alias="WEATHER_MCP_PORT")


@lru_cache
def get_settings() -> Settings:
    return Settings()
