from __future__ import annotations

from dataclasses import dataclass

from .agent_graph import AgentGraphRunner
from .agent_service import PythonActionAgentService
from .conversation_bridge import ConversationBridge
from .hybrid_retriever import HybridRetriever
from .intent_router import IntentRouter
from .intent_scorer import MultiIntentScorer
from .memory_store import SessionMemoryStore
from .policy_router import PolicyRouter
from .policy_service import PolicyService
from .query_refiner import QueryRefiner
from .schemas import AskRequest
from .semantic_cache import SemanticCache
from .stream_events import StreamEventCallback
from .summary_memory import SummaryMemoryService


@dataclass(frozen=True)
class OrchestratorResult:
    answer: str
    trace: dict


class AgentOrchestrator:
    def __init__(
        self,
        *,
        intent_router: IntentRouter,
        intent_scorer: MultiIntentScorer | None,
        bridge: ConversationBridge,
        memory_store: SessionMemoryStore,
        semantic_cache: SemanticCache,
        action_agent: PythonActionAgentService,
        policy_router: PolicyRouter | None,
        policy_service: PolicyService,
        hybrid_retriever: HybridRetriever,
        query_refiner: QueryRefiner | None = None,
        summary_memory: SummaryMemoryService | None = None,
    ) -> None:
        self._graph_runner = AgentGraphRunner(
            intent_router=intent_router,
            intent_scorer=intent_scorer,
            bridge=bridge,
            memory_store=memory_store,
            semantic_cache=semantic_cache,
            action_agent=action_agent,
            policy_router=policy_router,
            policy_service=policy_service,
            hybrid_retriever=hybrid_retriever,
            query_refiner=query_refiner,
            summary_memory=summary_memory,
        )

    async def answer(
        self,
        request: AskRequest,
        event_callback: StreamEventCallback | None = None,
    ) -> OrchestratorResult:
        result = await self._graph_runner.run(request, event_callback=event_callback)
        return OrchestratorResult(answer=result.answer, trace=result.trace)
