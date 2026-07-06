from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, TypedDict

from langgraph.graph import END, START, StateGraph

from .action_models import ActionPlan, ActionStep, AgentResult, StepStatus, StepType, ToolObservation
from .agent_service import (
    PythonActionAgentService,
    build_observation_detail,
    find_duplicate_side_effect,
    serialize_step_event,
    serialize_step_outline,
)
from .conversation_bridge import ConversationBridge
from .context_classifier import ContextDependencyDecision
from .hybrid_retriever import HybridRetriever
from .intent_router import IntentDecision, IntentLabel, IntentRouter
from .intent_scorer import MultiIntentScorer
from .logging_utils import get_logger, log_event
from .memory_store import SessionMemoryStore
from .policy_router import PolicyRouter
from .policy_service import PolicyAnswer, PolicyQueryPlan, PolicyService
from .query_refiner import QueryRefiner
from .schemas import AskRequest
from .semantic_cache import SemanticCache
from .stream_events import StreamEventCallback, emit_stream_event
from .summary_memory import SummaryMemoryService


class AgentGraphState(TypedDict, total=False):
    request: AskRequest
    event_callback: StreamEventCallback | None
    cache_hit: bool
    cache_answer: str
    cache_trace: dict
    refined_trace: dict | None
    routing_question: str
    retrieval_question: str
    intent_decision: IntentDecision
    scored_intent_trace: dict | None
    bridge_applied: bool
    bridge_reason: str
    context_dependency: dict | None
    effective_question: str
    policy_route_trace: dict | None
    rag_question: str
    rag_plan: PolicyQueryPlan
    rag_plan_trace: dict | None
    rag_contexts: list[str]
    rag_retrieval_trace: dict | None
    action_request: AskRequest
    action_plan: ActionPlan
    action_observations: list[ToolObservation]
    action_executed_side_effects: list[tuple[str, dict]]
    action_next_step_index: int
    action_final_observation: ToolObservation | None
    action_answer: str
    action_need_replan: bool
    action_replan_reason: str | None
    action_replanned: bool
    action_replan_count: int
    action_replan_reason_history: list[str]
    final_answer: str
    final_trace: dict


@dataclass(frozen=True)
class AgentGraphResult:
    answer: str
    trace: dict


class AgentGraphRunner:
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
        self._intent_router = intent_router
        self._intent_scorer = intent_scorer
        self._bridge = bridge
        self._memory_store = memory_store
        self._semantic_cache = semantic_cache
        self._action_agent = action_agent
        self._policy_router = policy_router
        self._policy_service = policy_service
        self._hybrid_retriever = hybrid_retriever
        self._query_refiner = query_refiner
        self._summary_memory = summary_memory
        self._logger = get_logger("python_agent.agent_graph")
        self._graph = self._build_graph()

    async def run(
        self,
        request: AskRequest,
        event_callback: StreamEventCallback | None = None,
    ) -> AgentGraphResult:
        direct = await self._try_direct_non_rag_answer(request, event_callback)
        if direct is not None:
            return direct
        state = await self._graph.ainvoke(
            {
                "request": request,
                "event_callback": event_callback,
            }
        )
        answer = str(state.get("final_answer") or "")
        trace = dict(state.get("final_trace") or {})
        return AgentGraphResult(answer=answer, trace=trace)

    async def _try_direct_non_rag_answer(
        self,
        request: AskRequest,
        event_callback: StreamEventCallback | None = None,
    ) -> AgentGraphResult | None:
        pending_result = await self._action_agent.handle_pending_confirmation(request, event_callback)
        if pending_result is not None:
            return self._direct_action_graph_result(request, pending_result, IntentLabel.ACTION)

        intent = self._intent_router.route(request.question)
        if intent.intent == IntentLabel.RAG:
            return None
        if intent.intent == IntentLabel.CHITCHAT:
            answer = "你好，我可以帮你处理12306的购票、退票、查票、订单、出行建议和规则问答。"
            self._memory_store.append(request.session_id, request.question, answer, intent.intent.value)
            summary = self._refresh_summary(request.session_id)
            return AgentGraphResult(
                answer=answer,
                trace={
                    "mode": "chitchat",
                    "intent": intent.intent.value,
                    "intentConfidence": intent.confidence,
                    "intentScores": intent.scores,
                    "sessionSummary": summary,
                },
            )
        result = await self._action_agent.answer(request, event_callback)
        return self._direct_action_graph_result(request, result, intent.intent)

    def _direct_action_graph_result(
        self,
        request: AskRequest,
        agent_result: AgentResult,
        intent: IntentLabel,
    ) -> AgentGraphResult:
        self._memory_store.append(request.session_id, request.question, agent_result.answer, intent.value)
        summary = self._refresh_summary(request.session_id)
        trace = {
            "mode": "action_agent",
            "intent": intent.value,
            "effectiveQuestion": request.question,
            "agentTrace": agent_result.trace(),
            "sessionSummary": summary,
        }
        return AgentGraphResult(answer=agent_result.answer, trace=trace)

    def _build_graph(self):
        graph = StateGraph(AgentGraphState)
        graph.add_node("semantic_cache_check", self._semantic_cache_check)
        graph.add_node("cache_finalize", self._cache_finalize)
        graph.add_node("query_refine", self._query_refine)
        graph.add_node("intent_score_route", self._intent_score_route)
        graph.add_node("bridge_context", self._bridge_context)
        graph.add_node("policy_route_gate", self._policy_route_gate)
        graph.add_node("rag_plan", self._rag_plan)
        graph.add_node("rag_retrieve", self._rag_retrieve)
        graph.add_node("rag_answer", self._rag_answer)
        graph.add_node("action_plan", self._action_plan)
        graph.add_node("action_execute", self._action_execute)
        graph.add_node("action_replan_gate", self._action_replan_gate)
        graph.add_node("action_respond", self._action_respond)
        graph.add_node("chitchat_answer", self._chitchat_answer)

        graph.add_edge(START, "semantic_cache_check")
        graph.add_conditional_edges(
            "semantic_cache_check",
            self._route_after_cache_check,
            {
                "cache_finalize": "cache_finalize",
                "query_refine": "query_refine",
            },
        )
        graph.add_edge("cache_finalize", END)
        graph.add_edge("query_refine", "intent_score_route")
        graph.add_edge("intent_score_route", "bridge_context")
        graph.add_conditional_edges(
            "bridge_context",
            self._route_after_bridge,
            {
                "policy_route_gate": "policy_route_gate",
                "action_plan": "action_plan",
                "chitchat_answer": "chitchat_answer",
            },
        )
        graph.add_edge("policy_route_gate", "rag_plan")
        graph.add_edge("rag_plan", "rag_retrieve")
        graph.add_edge("rag_retrieve", "rag_answer")
        graph.add_edge("rag_answer", END)
        graph.add_edge("action_plan", "action_execute")
        graph.add_edge("action_execute", "action_replan_gate")
        graph.add_conditional_edges(
            "action_replan_gate",
            self._route_after_action_replan_gate,
            {
                "action_plan": "action_plan",
                "action_execute": "action_execute",
                "action_respond": "action_respond",
            },
        )
        graph.add_edge("action_respond", END)
        graph.add_edge("chitchat_answer", END)
        return graph.compile()

    def _semantic_cache_check(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        cache_hit = self._semantic_cache.get(request.question)
        if cache_hit is None:
            return {"cache_hit": False}

        log_event(self._logger, "answer_cache_hit", sessionId=request.session_id, question=request.question)
        emit_stream_event(
            state.get("event_callback"),
            "semantic_cache_hit",
            {
                "sessionId": request.session_id,
                "question": request.question,
                "mode": "semantic_cache",
            },
        )
        return {
            "cache_hit": True,
            "cache_answer": cache_hit.answer,
            "cache_trace": {**cache_hit.trace, "mode": "semantic_cache"},
        }

    def _cache_finalize(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        trace = dict(state.get("cache_trace") or {})
        answer = str(state.get("cache_answer") or "")
        cached_intent = str(trace.get("intent") or "UNKNOWN")
        self._memory_store.append(request.session_id, request.question, answer, cached_intent)
        summary = self._refresh_summary(request.session_id)
        trace["sessionSummary"] = summary
        emit_stream_event(
            state.get("event_callback"),
            "memory_updated",
            {
                "summaryChars": len(str(summary or "")),
                "hasSummary": summary is not None,
                "source": "semantic_cache",
            },
        )
        return {
            "final_answer": answer,
            "final_trace": trace,
        }

    def _query_refine(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        refined = (
            self._query_refiner.refine(request.session_id, request.question)
            if self._query_refiner is not None
            else None
        )
        trace = refined.trace() if refined is not None else None
        if trace is not None:
            emit_stream_event(state.get("event_callback"), "query_refined", trace)
        return {
            "refined_trace": trace,
            "routing_question": refined.routing_question if refined is not None else request.question,
            "retrieval_question": refined.retrieval_question if refined is not None else request.question,
        }

    def _intent_score_route(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        refined_trace = state.get("refined_trace") or {}
        dependency = None
        if refined_trace.get("contextDependency") is not None:
            raw = refined_trace["contextDependency"]
            dependency = ContextDependencyDecision(
                dependent=bool(raw.get("dependent")),
                confidence=float(raw.get("confidence", 0.0)),
                reason=str(raw.get("reason", "")),
                source=str(raw.get("source", "heuristic")),
                signals=list(raw.get("signals", [])),
            )
        routing_question = str(state.get("routing_question") or request.question)
        retrieval_question = str(state.get("retrieval_question") or request.question)
        scored_intent = self._intent_scorer.score(routing_question, dependency) if self._intent_scorer is not None else None
        intent = self._intent_router.route(routing_question)
        if scored_intent is not None and scored_intent.recommended in {label.value for label in IntentLabel}:
            intent = IntentDecision(
                intent=IntentLabel(scored_intent.recommended),
                confidence=scored_intent.confidence,
                scores=scored_intent.scores,
                reason=scored_intent.reason,
            )
        log_event(
            self._logger,
            "intent_routed",
            sessionId=request.session_id,
            originalQuestion=request.question,
            routingQuestion=routing_question,
            retrievalQuestion=retrieval_question,
            intent=intent.intent.value,
            confidence=intent.confidence,
            scores=intent.scores,
            scorer=scored_intent.trace() if scored_intent is not None else None,
        )
        scored_trace = scored_intent.trace() if scored_intent is not None else None
        emit_stream_event(
            state.get("event_callback"),
            "intent_routed",
            {
                "sessionId": request.session_id,
                "originalQuestion": request.question,
                "routingQuestion": routing_question,
                "retrievalQuestion": retrieval_question,
                "intent": intent.intent.value,
                "intentConfidence": intent.confidence,
                "intentScores": intent.scores,
                "scorer": scored_trace,
            },
        )
        return {
            "intent_decision": intent,
            "scored_intent_trace": scored_trace,
        }

    def _bridge_context(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        intent = state["intent_decision"]
        routing_question = str(state.get("routing_question") or request.question)
        bridge = self._bridge.bridge(request.session_id, routing_question, intent.intent.value)
        effective_question = bridge.question if bridge.applied else routing_question
        emit_stream_event(
            state.get("event_callback"),
            "bridge_evaluated",
            {
                "bridgeApplied": bridge.applied,
                "bridgeReason": bridge.reason,
                "contextDependency": bridge.context_dependency,
                "effectiveQuestion": effective_question,
            },
        )
        return {
            "bridge_applied": bridge.applied,
            "bridge_reason": bridge.reason,
            "context_dependency": bridge.context_dependency,
            "effective_question": effective_question,
        }

    def _policy_route_gate(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        retrieval_question = str(state.get("retrieval_question") or request.question)
        raw_policy_route = self._policy_router.decide(retrieval_question) if self._policy_router is not None else None
        trace = raw_policy_route.trace() if raw_policy_route is not None else None
        if trace is not None:
            emit_stream_event(state.get("event_callback"), "policy_route", trace)
        return {"policy_route_trace": trace}

    def _rag_plan(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        effective_question = str(state.get("effective_question") or request.question)
        retrieval_question = str(state.get("retrieval_question") or request.question)
        rag_question = effective_question if state.get("bridge_applied") else retrieval_question
        plan = self._policy_service.build_query_plan(request.session_id, rag_question)
        plan_trace = self._policy_service.plan_trace(plan)
        emit_stream_event(state.get("event_callback"), "planned_rag", plan_trace)
        return {
            "rag_question": rag_question,
            "rag_plan": plan,
            "rag_plan_trace": plan_trace,
        }

    def _rag_retrieve(self, state: AgentGraphState) -> AgentGraphState:
        plan = state["rag_plan"]
        emit_stream_event(
            state.get("event_callback"),
            "retrieval_started",
            {
                "planner": plan.planner,
                "subQueries": plan.sub_queries,
                "complexity": plan.complexity,
            },
        )
        contexts, retrieval_trace = self._policy_service.retrieve_with_hybrid_plan(plan, self._hybrid_retriever)
        emit_stream_event(state.get("event_callback"), "retrieval_finished", retrieval_trace)
        return {
            "rag_contexts": contexts,
            "rag_retrieval_trace": retrieval_trace,
        }

    def _rag_answer(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        intent = state["intent_decision"]
        plan = state["rag_plan"]
        contexts = list(state.get("rag_contexts") or [])
        if not contexts:
            answer_text = "当前知识库里没有检索到直接相关的铁路规则，请换个更具体的问法。"
        else:
            answer_text = self._policy_service.generate_answer(plan.effective_question, contexts)
        policy_result = PolicyAnswer(
            answer=answer_text,
            contexts=contexts,
            effective_question=plan.effective_question,
            trace={
                "plannedRag": state.get("rag_plan_trace"),
                "retrieval": state.get("rag_retrieval_trace"),
            },
        )
        self._policy_service.remember_answer(request.session_id, str(state.get("rag_question") or request.question), policy_result)
        self._memory_store.append(request.session_id, request.question, policy_result.answer, intent.intent.value)
        summary = self._refresh_summary(request.session_id)
        trace = {
            "mode": "policy_rag",
            "intent": intent.intent.value,
            "intentConfidence": intent.confidence,
            "intentScores": intent.scores,
            "bridgeApplied": bool(state.get("bridge_applied")),
            "bridgeReason": state.get("bridge_reason"),
            "contextDependency": state.get("context_dependency"),
            "effectiveQuestion": policy_result.effective_question,
            "queryRewrite": state.get("refined_trace"),
            "policyRoute": state.get("policy_route_trace"),
            "contexts": policy_result.contexts,
            "retrieval": state.get("rag_retrieval_trace"),
            "plannedRag": state.get("rag_plan_trace"),
            "sessionSummary": summary,
        }
        emit_stream_event(
            state.get("event_callback"),
            "memory_updated",
            {
                "summaryChars": len(str(summary or "")),
                "hasSummary": summary is not None,
                "source": "policy_rag",
            },
        )
        self._semantic_cache.put(request.question, policy_result.answer, trace)
        log_event(
            self._logger,
            "answer_policy_rag",
            sessionId=request.session_id,
            effectiveQuestion=policy_result.effective_question,
            plannedRag=trace.get("plannedRag"),
            retrieval=trace.get("retrieval"),
        )
        return {
            "final_answer": policy_result.answer,
            "final_trace": trace,
        }

    def _action_plan(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        replan_reason = state.get("action_replan_reason")
        previous_plan = state.get("action_plan")
        effective_question = str(state.get("effective_question") or request.question)
        action_request = request.model_copy(update={"question": effective_question})
        plan = self._action_agent.build_plan(
            action_request,
            previous_plan=previous_plan,
            replan_reason=replan_reason,
        )
        if plan is None:
            plan = self._action_agent.build_fallback_plan("当前信息不足，请补充后再继续。")
        replan_count = int(state.get("action_replan_count") or 0)
        emit_stream_event(
            state.get("event_callback"),
            "plan_generated",
            {
                "goal": plan.goal,
                "replanned": replan_count > 0,
                "replanCount": replan_count,
                "fallbackUsed": False,
                "stepCount": len(plan.steps),
                "steps": [serialize_step_outline(step) for step in plan.steps],
            },
        )
        return {
            "action_request": action_request,
            "action_plan": plan,
            "action_observations": [],
            "action_executed_side_effects": [],
            "action_next_step_index": 0,
            "action_final_observation": None,
            "action_answer": "",
            "action_need_replan": False,
            "action_replan_reason": None,
            "action_replanned": replan_count > 0,
        }

    async def _action_execute(self, state: AgentGraphState) -> AgentGraphState:
        plan = state["action_plan"]
        request = state["action_request"]
        event_callback = state.get("event_callback")
        index = int(state.get("action_next_step_index") or 0)
        observations = list(state.get("action_observations") or [])
        executed_side_effects = list(state.get("action_executed_side_effects") or [])
        if index >= len(plan.steps):
            fallback = observations[-1].message if observations else "抱歉，当前没有可执行结果。"
            return {
                "action_answer": fallback,
                "action_final_observation": observations[-1] if observations else None,
                "action_need_replan": False,
                "action_replan_reason": None,
            }

        step = plan.steps[index]
        if step.type == StepType.RESPOND:
            step.status = StepStatus.SUCCESS
            message = self._action_agent.build_respond_message(request, step, observations)
            final_observation = ToolObservation(tool_name="respond", success=True, code="RESPOND", message=message)
            step.observation = {"code": final_observation.code, "message": final_observation.message}
            step.observation_detail = build_observation_detail(final_observation, source="RESPOND")
            emit_stream_event(event_callback, "tool_executed", serialize_step_event(step))
            return {
                "action_answer": final_observation.message,
                "action_final_observation": final_observation,
                "action_need_replan": False,
                "action_replan_reason": None,
            }

        step.status = StepStatus.RUNNING
        duplicate = find_duplicate_side_effect(step, executed_side_effects, self._action_agent.side_effect_tools)
        if duplicate is not None:
            duplicate_observation = ToolObservation(
                tool_name=str(step.tool),
                success=False,
                code="DUPLICATE_SIDE_EFFECT_STEP",
                message=f"检测到重复副作用步骤，已跳过：{duplicate}",
                retryable=False,
            )
            step.status = StepStatus.FAILED
            step.observation = {
                "toolName": duplicate_observation.tool_name,
                "success": duplicate_observation.success,
                "code": duplicate_observation.code,
                "message": duplicate_observation.message,
                "data": duplicate_observation.data,
            }
            step.observation_detail = build_observation_detail(duplicate_observation)
            emit_stream_event(event_callback, "tool_executed", serialize_step_event(step))
            return {
                "action_observations": observations,
                "action_executed_side_effects": executed_side_effects,
                "action_final_observation": duplicate_observation,
                "action_need_replan": True,
                "action_replan_reason": self._action_agent.build_replan_reason(step.id, duplicate_observation, "计划重复安排了副作用步骤"),
            }

        confirmation_observation = self._action_agent.build_confirmation_observation(step, request)
        if confirmation_observation is not None:
            step.status = StepStatus.FAILED
            step.observation = {
                "toolName": confirmation_observation.tool_name,
                "success": confirmation_observation.success,
                "code": confirmation_observation.code,
                "message": confirmation_observation.message,
                "data": confirmation_observation.data,
            }
            step.observation_detail = build_observation_detail(confirmation_observation, source="CONFIRMATION")
            observations.append(confirmation_observation)
            emit_stream_event(event_callback, "tool_executed", serialize_step_event(step))
            self._action_agent.skip_remaining_steps(plan.steps, index + 1)
            return {
                "action_observations": observations,
                "action_executed_side_effects": executed_side_effects,
                "action_final_observation": confirmation_observation,
                "action_answer": confirmation_observation.message,
                "action_need_replan": False,
                "action_replan_reason": None,
            }

        try:
            observation = await self._action_agent.execute_step(step, request)
        except ValueError as exc:
            observation = ToolObservation(
                tool_name=str(step.tool),
                success=False,
                code="INVALID_ARGS",
                message=str(exc),
                retryable=False,
            )

        step.status = StepStatus.SUCCESS if observation.success else StepStatus.FAILED
        step.observation = {
            "toolName": observation.tool_name,
            "success": observation.success,
            "code": observation.code,
            "message": observation.message,
            "data": observation.data,
        }
        step.observation_detail = build_observation_detail(observation)
        observations.append(observation)
        emit_stream_event(event_callback, "tool_executed", serialize_step_event(step))

        if step.tool is not None and str(step.tool) in self._action_agent.side_effect_tools and observation.success:
            executed_side_effects.append((str(step.tool), dict(step.args or {})))

        need_replan = observation.code == "UNKNOWN_TOOL" or not observation.message
        replan_reason = None
        if observation.code == "UNKNOWN_TOOL":
            replan_reason = self._action_agent.build_replan_reason(step.id, observation, "执行器返回未知工具")
        elif not observation.message:
            replan_reason = self._action_agent.build_replan_reason(step.id, observation, "执行后没有返回可展示结果")

        if observation.terminal:
            self._action_agent.skip_remaining_steps(plan.steps, index + 1)
            return {
                "action_observations": observations,
                "action_executed_side_effects": executed_side_effects,
                "action_final_observation": observation,
                "action_answer": observation.message,
                "action_need_replan": False,
                "action_replan_reason": None,
            }

        return {
            "action_observations": observations,
            "action_executed_side_effects": executed_side_effects,
            "action_next_step_index": index + 1,
            "action_final_observation": observation,
            "action_need_replan": need_replan,
            "action_replan_reason": replan_reason,
        }

    def _action_replan_gate(self, state: AgentGraphState) -> AgentGraphState:
        if state.get("action_need_replan"):
            replan_reason = state.get("action_replan_reason")
            replan_reason_history = list(state.get("action_replan_reason_history") or [])
            if replan_reason:
                replan_reason_history.append(replan_reason)
                emit_stream_event(
                    state.get("event_callback"),
                    "replan_triggered",
                    {
                        "reason": replan_reason,
                        "replanCount": len(replan_reason_history),
                        "goal": state["action_plan"].goal,
                    },
                )
            return {
                "action_replanned": True,
                "action_replan_count": len(replan_reason_history),
                "action_replan_reason_history": replan_reason_history,
            }
        return {
            "action_replan_reason": None,
        }

    def _action_respond(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        intent = state["intent_decision"]
        plan = state["action_plan"]
        final_observation = state.get("action_final_observation")
        answer = str(state.get("action_answer") or "")
        if not answer:
            fallback_plan = self._action_agent.build_fallback_plan("抱歉，我暂时无法稳定生成执行计划，请稍后重试。")
            final_observation = ToolObservation(
                tool_name="respond",
                success=False,
                code="FALLBACK",
                message="抱歉，我暂时无法稳定生成执行计划，请稍后重试。",
            )
            answer = final_observation.message
            plan = fallback_plan
        agent_result = AgentResult(
            answer=answer,
            plan=plan,
            final_observation=final_observation,
            replanned=bool(state.get("action_replanned")),
            fallback_used=bool(final_observation is not None and final_observation.code == "FALLBACK"),
            replan_count=int(state.get("action_replan_count") or 0),
            replan_reason_history=tuple(state.get("action_replan_reason_history") or []),
        )
        self._memory_store.append(request.session_id, request.question, answer, intent.intent.value)
        summary = self._refresh_summary(request.session_id)
        trace = {
            "mode": "action_agent",
            "intent": intent.intent.value,
            "intentConfidence": intent.confidence,
            "intentScores": intent.scores,
            "bridgeApplied": bool(state.get("bridge_applied")),
            "bridgeReason": state.get("bridge_reason"),
            "contextDependency": state.get("context_dependency"),
            "effectiveQuestion": state["action_request"].question,
            "queryRewrite": state.get("refined_trace"),
            "agentTrace": agent_result.trace(),
            "sessionSummary": summary,
        }
        emit_stream_event(
            state.get("event_callback"),
            "memory_updated",
            {
                "summaryChars": len(str(summary or "")),
                "hasSummary": summary is not None,
                "source": "action_agent",
            },
        )
        self._semantic_cache.put(request.question, answer, trace)
        log_event(
            self._logger,
            "answer_action",
            sessionId=request.session_id,
            effectiveQuestion=state["action_request"].question,
            agentTrace=trace.get("agentTrace"),
        )
        return {
            "final_answer": answer,
            "final_trace": trace,
        }

    def _chitchat_answer(self, state: AgentGraphState) -> AgentGraphState:
        request = state["request"]
        intent = state["intent_decision"]
        answer = "你好，我可以帮你处理12306的购票、退票、查票、订单和规则问答。"
        self._memory_store.append(request.session_id, request.question, answer, intent.intent.value)
        summary = self._refresh_summary(request.session_id)
        trace = {
            "mode": "chitchat",
            "intent": intent.intent.value,
            "intentConfidence": intent.confidence,
            "intentScores": intent.scores,
            "queryRewrite": state.get("refined_trace"),
            "sessionSummary": summary,
        }
        emit_stream_event(
            state.get("event_callback"),
            "memory_updated",
            {
                "summaryChars": len(str(summary or "")),
                "hasSummary": summary is not None,
                "source": "chitchat",
            },
        )
        log_event(self._logger, "answer_chitchat", sessionId=request.session_id, question=request.question)
        return {
            "final_answer": answer,
            "final_trace": trace,
        }

    def _refresh_summary(self, session_id: str) -> str | None:
        if self._summary_memory is None:
            return None
        return self._summary_memory.refresh_summary_if_needed(session_id)

    @staticmethod
    def _route_after_cache_check(state: AgentGraphState) -> Literal["cache_finalize", "query_refine"]:
        return "cache_finalize" if state.get("cache_hit") else "query_refine"

    @staticmethod
    def _route_after_bridge(state: AgentGraphState) -> Literal["policy_route_gate", "action_plan", "chitchat_answer"]:
        intent = state["intent_decision"].intent
        if intent == IntentLabel.RAG:
            return "policy_route_gate"
        if intent in {IntentLabel.ACTION, IntentLabel.TICKET, IntentLabel.UNCERTAIN}:
            return "action_plan"
        return "chitchat_answer"

    def _route_after_action_replan_gate(
        self,
        state: AgentGraphState,
    ) -> Literal["action_plan", "action_execute", "action_respond"]:
        if state.get("action_need_replan"):
            if int(state.get("action_replan_count") or 0) <= self._action_agent.max_replan_times:
                return "action_plan"
            return "action_respond"
        index = int(state.get("action_next_step_index") or 0)
        plan = state.get("action_plan")
        if plan is not None and index < len(plan.steps) and not state.get("action_answer"):
            return "action_execute"
        return "action_respond"
