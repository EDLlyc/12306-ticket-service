import asyncio
from collections.abc import AsyncIterator
from dataclasses import asdict
import json
from typing import Any

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from sse_starlette.sse import EventSourceResponse

from .agent_service import PythonActionAgentService
from .confirmation_store import InMemoryConfirmationStore, RedisBackedConfirmationStore
from .conversation_bridge import ConversationBridge
from .context_classifier import ContextDependencyClassifier
from .document_ingestion_service import DocumentIngestionService, IngestionOptions
from .eval_service import EvalService
from .hybrid_retriever import HybridRetriever
from .intent_router import IntentRouter
from .intent_scorer import MultiIntentScorer
from .java_client import JavaTicketClient
from .langchain_tools import langchain_tool_schema, tool_calling_schema
from .llm_client import ZhipuLLMClient
from .llm_planner import LLMActionPlanner
from .logging_utils import configure_logging, get_logger, log_event
from .memory_store import RedisBackedSessionMemoryStore, SessionMemoryStore
from .ollama_client import OllamaAuxiliaryModelClient
from .orchestrator import AgentOrchestrator
from .planner import ActionPlanner
from .policy_complexity import PolicyComplexityClassifier
from .policy_router import PolicyRouter
from .policy_service import PolicyService
from .query_refiner import QueryRefiner
from .redis_store import build_redis_client
from .response_summarizer import ResponseSummarizer
from .schemas import AskRequest, AskResponse, DebugCacheClearResponse, DebugClearCacheRequest, DebugSessionResponse, DocumentIngestRequest, DocumentIngestResponse, EvalAskResponse, OllamaChatRequest, OllamaChatResponse, OllamaSwitchModelRequest
from .semantic_cache import RedisBackedSemanticCache, SemanticCache
from .settings import get_settings
from .summary_memory import SummaryMemoryService
from .tool_executor import ToolExecutor
from .weather_mcp import WeatherMcpClient


settings = get_settings()
configure_logging()
logger = get_logger("python_agent.main")
app = FastAPI(title="12306 Python Agent Service", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://127.0.0.1:8899",
        "http://localhost:8899",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
async def startup() -> None:
    java_client = JavaTicketClient(settings)
    ollama_client = OllamaAuxiliaryModelClient(settings)
    llm_client = ZhipuLLMClient(settings, ollama_client=ollama_client)
    weather_mcp_client = WeatherMcpClient(settings)
    redis_client = build_redis_client(settings)
    memory_fallback = SessionMemoryStore(settings.session_memory_limit)
    memory_store = RedisBackedSessionMemoryStore(redis_client, settings.session_memory_limit, memory_fallback)
    confirmation_store = RedisBackedConfirmationStore(redis_client, InMemoryConfirmationStore())
    semantic_fallback = SemanticCache(settings.semantic_cache_limit)
    semantic_cache = RedisBackedSemanticCache(redis_client, settings.semantic_cache_limit, semantic_fallback)
    intent_router = IntentRouter(settings.intent_high_confidence, settings.intent_margin_threshold)
    intent_scorer = MultiIntentScorer(llm_client)
    context_classifier = ContextDependencyClassifier(llm_client)
    complexity_classifier = PolicyComplexityClassifier(llm_client)
    policy_router = PolicyRouter(llm_client)
    policy_service = PolicyService(settings, llm_client, complexity_classifier=complexity_classifier)
    document_ingestion_service = DocumentIngestionService(settings, llm_client=llm_client)
    bridge = ConversationBridge(memory_store, context_classifier)
    hybrid_retriever = HybridRetriever(settings, llm_client=llm_client)
    query_refiner = QueryRefiner(memory_store, llm_client, context_classifier)
    summary_memory = SummaryMemoryService(llm_client, memory_store)
    app.state.java_client = java_client
    app.state.llm_client = llm_client
    app.state.ollama_client = ollama_client
    app.state.weather_mcp_client = weather_mcp_client
    app.state.policy_service = policy_service
    app.state.document_ingestion_service = document_ingestion_service
    app.state.memory_store = memory_store
    app.state.semantic_cache = semantic_cache
    app.state.agent_service = PythonActionAgentService(
        planner=ActionPlanner(),
        executor=ToolExecutor(java_client, weather_mcp_client),
        llm_planner=LLMActionPlanner(llm_client),
        summarizer=ResponseSummarizer(llm_client),
        confirmation_store=confirmation_store,
    )
    app.state.langchain_safe_tool_schema = langchain_tool_schema(low_risk_only=True)
    app.state.langchain_all_tool_schema = langchain_tool_schema(low_risk_only=False)
    app.state.tool_calling_schema = tool_calling_schema(low_risk_only=False)
    app.state.orchestrator = AgentOrchestrator(
        intent_router=intent_router,
        intent_scorer=intent_scorer,
        bridge=bridge,
        memory_store=memory_store,
        semantic_cache=semantic_cache,
        action_agent=app.state.agent_service,
        policy_router=policy_router,
        policy_service=policy_service,
        hybrid_retriever=hybrid_retriever,
        query_refiner=query_refiner,
        summary_memory=summary_memory,
    )
    app.state.eval_service = EvalService(app.state.orchestrator, memory_store)
    log_event(
        logger,
        "startup_complete",
        port=settings.port,
        javaBaseUrl=settings.java_ticket_base_url,
        milvusEnabled=settings.milvus_enabled,
        opensearchEnabled=settings.opensearch_enabled,
        rerankEnabled=settings.rerank_enabled,
        plannedRagEnabled=settings.planned_rag_enabled,
        ollamaEnabled=settings.ollama_enabled,
        ollamaAuxModel=ollama_client.current_model,
        weatherMcpConfigured=bool(settings.weather_mcp_endpoint),
    )


@app.on_event("shutdown")
async def shutdown() -> None:
    await app.state.java_client.close()
    log_event(logger, "shutdown_complete")


@app.get("/health")
async def health() -> dict[str, object]:
    java_core = await app.state.java_client.health()
    log_event(logger, "health_check", javaCore=java_core)
    return {
        "status": "ok",
        "service": "python_agent",
        "java_core": java_core,
    }


@app.post("/ask", response_model=AskResponse)
async def ask(request: AskRequest) -> AskResponse:
    log_event(logger, "ask_request", sessionId=request.session_id, question=request.question, username=request.username)
    result = await app.state.orchestrator.answer(request)
    log_event(logger, "ask_response", sessionId=request.session_id, trace=result.trace)
    return AskResponse(
        session_id=request.session_id,
        answer=result.answer,
        tool_result=result.trace,
    )


@app.get("/ask", response_model=AskResponse)
async def ask_get(
    q: str,
    sessionId: str = "web-session",
    username: str | None = None,
    token: str | None = None,
) -> AskResponse:
    request = AskRequest(session_id=sessionId, question=q, username=username, token=token)
    return await ask(request)


@app.get("/eval/ask", response_model=EvalAskResponse)
async def eval_ask(
    q: str,
    sessionId: str = "eval-session",
    username: str = "eval-user",
) -> EvalAskResponse:
    request = AskRequest(session_id=sessionId, question=q, username=username)
    log_event(logger, "eval_ask_request", sessionId=sessionId, question=q, username=username)
    eval_result = await app.state.eval_service.ask_with_contexts(sessionId, q, username)
    payload = EvalAskResponse(
        answer=eval_result["answer"],
        contexts=eval_result["contexts"],
        trace=eval_result["trace"],
    )
    log_event(
        logger,
        "eval_ask_response",
        sessionId=sessionId,
        contextCount=len(payload.contexts),
        mode=payload.trace.get("mode") if isinstance(payload.trace, dict) else None,
    )
    return payload


@app.get("/eval/replay")
async def eval_replay(
    q: str,
    sessionId: str = "eval-session",
    username: str = "eval-user",
    warmupQuestion: list[str] | None = None,
) -> dict[str, object]:
    log_event(logger, "eval_replay_request", sessionId=sessionId, question=q, username=username)
    replay = await app.state.eval_service.replay_with_and_without_memory(sessionId, q, username, warmup_questions=warmupQuestion)
    payload = {
        "sessionId": replay.session_id,
        "question": replay.question,
        "processed_question": replay.processed_question,
        "no_memory_processed_question": replay.no_memory_processed_question,
        "contexts": replay.contexts,
        "with_memory_answer": replay.with_memory_answer,
        "no_memory_answer": replay.no_memory_answer,
        "warmupQuestions": replay.warmup_questions,
        "withMemoryTrace": replay.with_memory_trace,
        "noMemoryTrace": replay.no_memory_trace,
        "metrics": replay.metrics,
    }
    log_event(logger, "eval_replay_response", sessionId=sessionId, metrics=replay.metrics)
    return payload


@app.post("/ask/stream")
async def ask_stream(request: AskRequest) -> EventSourceResponse:
    log_event(logger, "ask_stream_request", sessionId=request.session_id, question=request.question, username=request.username)
    return EventSourceResponse(
        _stream_answer(request),
        ping=3,
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@app.get("/ask/stream")
async def ask_stream_get(
    q: str,
    sessionId: str = "web-session",
    username: str | None = None,
    token: str | None = None,
) -> EventSourceResponse:
    request = AskRequest(session_id=sessionId, question=q, username=username, token=token)
    return await ask_stream(request)


@app.get("/debug/session/{session_id}", response_model=DebugSessionResponse)
async def debug_session(session_id: str) -> DebugSessionResponse:
    turns = app.state.memory_store.recent(session_id)
    summary = app.state.memory_store.get_summary(session_id)
    payload = DebugSessionResponse(
        session_id=session_id,
        turns=[{"question": turn.question, "answer": turn.answer, "intent": turn.intent} for turn in turns],
        summary=summary,
    )
    log_event(logger, "debug_session", sessionId=session_id, turnCount=len(payload.turns), hasSummary=summary is not None)
    return payload


@app.post("/debug/cache/clear", response_model=DebugCacheClearResponse)
async def debug_clear_cache(request: DebugClearCacheRequest) -> DebugCacheClearResponse:
    cleared = app.state.semantic_cache.clear(request.question)
    log_event(logger, "debug_cache_clear", question=request.question, cleared=cleared)
    return DebugCacheClearResponse(cleared=cleared, question=request.question)


@app.post("/debug/session/{session_id}/clear")
async def debug_clear_session(session_id: str) -> dict[str, object]:
    app.state.memory_store.clear(session_id)
    log_event(logger, "debug_session_clear", sessionId=session_id)
    return {"session_id": session_id, "cleared": True}


@app.post("/admin/documents/ingest", response_model=DocumentIngestResponse)
async def ingest_documents(request: DocumentIngestRequest) -> DocumentIngestResponse:
    log_event(
        logger,
        "document_ingest_request",
        sourcePath=request.source_path,
        dryRun=request.dry_run,
        force=request.force,
        writeOpenSearch=request.write_opensearch,
        writeMilvus=request.write_milvus,
    )
    result = await asyncio.to_thread(
        app.state.document_ingestion_service.ingest,
        IngestionOptions(
            source_path=request.source_path,
            write_opensearch=request.write_opensearch,
            write_milvus=request.write_milvus,
            force=request.force,
            dry_run=request.dry_run,
        ),
    )
    return DocumentIngestResponse(**asdict(result))


@app.get("/admin/documents/ingest/jobs")
async def recent_document_ingest_jobs(limit: int = 20) -> dict[str, object]:
    jobs = app.state.document_ingestion_service.recent_jobs(limit)
    return {"jobs": jobs, "count": len(jobs)}


@app.get("/debug/runtime")
async def debug_runtime() -> dict[str, object]:
    java_core = await app.state.java_client.health()
    payload = {
        "service": "python_agent",
        "port": settings.port,
        "java_ticket_base_url": settings.java_ticket_base_url,
        "zhipu_configured": bool(settings.zhipu_api_key),
        "ollama_enabled": settings.ollama_enabled,
        "ollama_base_url": settings.ollama_base_url,
        "ollama_aux_model": app.state.ollama_client.current_model,
        "ollama_available_models": app.state.ollama_client.list_models() if settings.ollama_enabled else [],
        "milvus_enabled": settings.milvus_enabled,
        "opensearch_enabled": settings.opensearch_enabled,
        "ingestion_state_path": settings.ingestion_state_path,
        "rerank_enabled": settings.rerank_enabled,
        "planned_rag_enabled": settings.planned_rag_enabled,
        "weather_mcp": {
            "configured": bool(settings.weather_mcp_endpoint),
            "endpoint": settings.weather_mcp_endpoint,
            "tool_name": settings.weather_mcp_tool_name,
            "timeout_ms": settings.weather_mcp_timeout_ms,
        },
        "langchain_safe_tool_count": len(getattr(app.state, "langchain_safe_tool_schema", [])),
        "langchain_safe_tools": getattr(app.state, "langchain_safe_tool_schema", []),
        "langchain_all_tool_count": len(getattr(app.state, "langchain_all_tool_schema", [])),
        "tool_calling_schema_count": len(getattr(app.state, "tool_calling_schema", [])),
        "redis": {
            "host": settings.redis_host,
            "port": settings.redis_port,
            "db": settings.redis_db,
            "connected": app.state.memory_store._redis is not None if hasattr(app.state.memory_store, "_redis") else False,
        },
        "java_core": java_core,
    }
    log_event(logger, "debug_runtime", payload=payload)
    return payload


@app.post("/ollama/chat", response_model=OllamaChatResponse)
async def ollama_chat(request: OllamaChatRequest) -> OllamaChatResponse:
    system_prompt = request.system_prompt or "你是一个简洁、自然的中文助手。"
    current_model = app.state.ollama_client.current_model
    log_event(logger, "ollama_chat_request", message=request.message, sessionId=request.session_id)
    answer = app.state.ollama_client.chat(
        system_prompt=system_prompt,
        user_prompt=request.message,
        model=current_model,
        temperature=0.7,
        max_tokens=settings.ollama_chat_max_tokens,
    )
    if not answer:
        log_event(logger, "ollama_chat_empty", model=current_model, sessionId=request.session_id)
        answer = "本地模型当前没有返回结果，请稍后重试。"
    else:
        log_event(
            logger,
            "ollama_chat_response",
            model=current_model,
            sessionId=request.session_id,
            answerChars=len(answer),
        )
    return OllamaChatResponse(answer=answer, model=current_model)


@app.get("/ollama/chat")
async def ollama_chat_get(
    message: str,
    system_prompt: str | None = None,
) -> OllamaChatResponse:
    return await ollama_chat(OllamaChatRequest(message=message, system_prompt=system_prompt))


@app.post("/ollama/reload")
async def ollama_reload() -> dict[str, object]:
    current_model = app.state.ollama_client.current_model
    unloaded = app.state.ollama_client.unload_model(current_model)
    warmed = False
    if unloaded:
        warmed = (
            app.state.ollama_client.chat(
                system_prompt="你是一个简洁、自然的中文助手。",
                user_prompt="你好",
                model=current_model,
                temperature=0.2,
                max_tokens=32,
            )
            is not None
        )
    log_event(logger, "ollama_reload", model=current_model, unloaded=unloaded, warmed=warmed)
    return {
        "ok": unloaded and warmed,
        "model": current_model,
        "unloaded": unloaded,
        "warmed": warmed,
    }


@app.get("/ollama/models")
async def ollama_models() -> dict[str, object]:
    current_model = app.state.ollama_client.current_model
    models = app.state.ollama_client.list_models()
    payload = {
        "current_model": current_model,
        "models": models,
    }
    log_event(logger, "ollama_models", currentModel=current_model, modelCount=len(models))
    return payload


@app.post("/ollama/model")
async def ollama_switch_model(request: OllamaSwitchModelRequest) -> dict[str, object]:
    requested_model = request.model.strip()
    models = app.state.ollama_client.list_models()
    if models and requested_model not in models:
        return {
            "ok": False,
            "model": app.state.ollama_client.current_model,
            "requested_model": requested_model,
            "available_models": models,
            "reason": "model_not_found",
        }

    previous_model = app.state.ollama_client.current_model
    app.state.ollama_client.set_current_model(requested_model)
    warmed = (
        app.state.ollama_client.chat(
            system_prompt="你是一个简洁、自然的中文助手。",
            user_prompt="你好",
            model=requested_model,
            temperature=0.2,
            max_tokens=32,
        )
        is not None
    )
    log_event(
        logger,
        "ollama_switch_model",
        previousModel=previous_model,
        model=requested_model,
        warmed=warmed,
    )
    return {
        "ok": warmed,
        "model": requested_model,
        "previous_model": previous_model,
        "warmed": warmed,
        "available_models": models or [requested_model],
    }


@app.get("/ollama/chat/stream")
async def ollama_chat_stream(
    message: str,
    system_prompt: str | None = None,
) -> EventSourceResponse:
    async def stream() -> AsyncIterator[dict[str, str]]:
        system = system_prompt or "你是一个简洁、自然的中文助手。"
        current_model = app.state.ollama_client.current_model
        log_event(logger, "ollama_chat_stream_request", message=message)
        yield _sse_event("meta", {"model": current_model})
        loop = asyncio.get_running_loop()
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()

        def produce() -> None:
            for item in app.state.ollama_client.stream_chat(
                system_prompt=system,
                user_prompt=message,
                model=current_model,
                temperature=0.7,
                max_tokens=settings.ollama_chat_max_tokens,
            ):
                loop.call_soon_threadsafe(queue.put_nowait, item)
            loop.call_soon_threadsafe(queue.put_nowait, {"type": "__complete__"})

        producer_task = asyncio.create_task(asyncio.to_thread(produce))
        emitted = False
        try:
            while True:
                item = await queue.get()
                item_type = item.get("type")
                if item_type == "__complete__":
                    break
                if item_type == "chunk":
                    emitted = True
                    yield _sse_event("message", item.get("content", ""))
                elif item_type == "error":
                    error_text = "本地模型生成超时，请缩短问题或稍后重试。"
                    if not emitted:
                        yield _sse_event("message", error_text)
                    yield _sse_event("error", {"model": item.get("model"), "error": item.get("error")})
                elif item_type == "empty" and not emitted:
                    yield _sse_event("message", "本地模型当前没有返回结果，请稍后重试。")
                elif item_type == "done":
                    log_event(
                        logger,
                        "ollama_chat_stream_done",
                        model=item.get("model"),
                        chunkCount=item.get("chunk_count"),
                        contentChars=item.get("content_chars"),
                        doneReason=item.get("done_reason"),
                    )
        finally:
            await producer_task
        yield _sse_event("done", {"model": current_model})

    return EventSourceResponse(
        stream(),
        ping=3,
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


async def _stream_answer(request: AskRequest) -> AsyncIterator[dict[str, str]]:
    yield _sse_event(
        "request_received",
        {
            "sessionId": request.session_id,
            "username": request.username,
            "question": request.question,
        },
    )
    queue: asyncio.Queue[dict[str, str]] = asyncio.Queue()

    def emit(event: str, data: dict | list | str) -> None:
        queue.put_nowait(_sse_event(event, data))

    task = asyncio.create_task(app.state.orchestrator.answer(request, event_callback=emit))
    result = None
    failure = None
    while True:
        if task.done() and result is None and failure is None:
            try:
                result = task.result()
            except Exception as exc:  # pragma: no cover - integration-visible
                failure = exc
                log_event(logger, "ask_stream_error", sessionId=request.session_id, question=request.question, error=str(exc))
                emit("error", {"message": str(exc)})
        try:
            event = await asyncio.wait_for(queue.get(), timeout=0.25)
            yield event
            continue
        except TimeoutError:
            pass
        if task.done() and queue.empty():
            break

    if failure is not None:
        yield _sse_event("done", "[DONE]")
        return
    if result is None:
        yield _sse_event("error", {"message": "stream_result_missing"})
        yield _sse_event("done", "[DONE]")
        return
    yield _sse_event(
        "final_answer",
        {
            "mode": result.trace.get("mode") if isinstance(result.trace, dict) else None,
            "answerLength": len(result.answer or ""),
        },
    )
    if result.trace:
        yield _sse_event("trace", result.trace)
    async for event in _stream_text(result.answer, result.trace):
        yield event


async def _stream_text(text: str, trace: dict | None = None) -> AsyncIterator[dict[str, str]]:
    chunk_size = max(settings.response_chunk_size, 1)
    for start in range(0, len(text), chunk_size):
        yield _sse_event("message", text[start:start + chunk_size])
    yield _sse_event("done", "[DONE]")


def _sse_event(event: str, data: dict | list | str) -> dict[str, str]:
    if isinstance(data, str):
        payload = data
    else:
        payload = json.dumps(data, ensure_ascii=False, default=str)
    return {"event": event, "data": payload}
