from __future__ import annotations

from dataclasses import dataclass
import re

from .context_classifier import ContextDependencyClassifier, build_state_card, is_short_follow_up
from .intent_router import POLICY_INTENT_HINTS, POLICY_KEYWORDS
from .llm_client import ZhipuLLMClient
from .logging_utils import get_logger, log_event
from .memory_store import SessionMemoryStore


LEADING_FILLERS = (
    "请问",
    "麻烦问下",
    "麻烦问一下",
    "想问下",
    "想问一下",
    "帮我看下",
    "帮我问下",
)
COLLOQUIAL_REPLACEMENTS = (
    ("咋办", "怎么办"),
    ("咋处理", "怎么处理"),
    ("咋规定", "怎么规定"),
    ("咋弄", "怎么办理"),
    ("咋买", "怎么买"),
    ("咋退", "怎么退"),
    ("咋改", "怎么改签"),
    ("咋", "怎么"),
)


@dataclass(frozen=True)
class RefinedQuery:
    original_question: str
    normalized_question: str
    routing_question: str
    retrieval_question: str
    applied: bool
    used_llm: bool
    reasons: list[str]
    context_dependency: dict | None = None

    def trace(self) -> dict:
        return {
            "applied": self.applied,
            "usedLlm": self.used_llm,
            "normalizedQuestion": self.normalized_question,
            "routingQuestion": self.routing_question,
            "retrievalQuestion": self.retrieval_question,
            "reasons": self.reasons,
            "contextDependency": self.context_dependency,
        }


class QueryRefiner:
    def __init__(
        self,
        memory_store: SessionMemoryStore,
        llm_client: ZhipuLLMClient,
        context_classifier: ContextDependencyClassifier,
    ) -> None:
        self._memory_store = memory_store
        self._llm_client = llm_client
        self._context_classifier = context_classifier
        self._logger = get_logger("python_agent.query_refiner")
        self._last_dependency = None

    def refine(self, session_id: str, question: str) -> RefinedQuery:
        original = question.strip()
        normalized = normalize_question_text(original)
        reasons: list[str] = []
        retrieval_question = normalized
        used_llm = False

        latest = self._memory_store.latest(session_id)
        dependency = self._context_classifier.classify(normalized, latest)
        self._last_dependency = dependency
        state_card = build_state_card(latest.question, latest.answer) if latest is not None else None

        if dependency.dependent and state_card and should_use_llm_refiner(normalized):
            llm_rewrite = self._llm_rewrite(normalized, state_card)
            if llm_rewrite and llm_rewrite != normalized:
                retrieval_question = normalize_question_text(llm_rewrite)
                reasons.append("llm_refine")
                used_llm = True

        if retrieval_question == normalized:
            heuristic_question, heuristic_reasons = heuristic_refine(
                normalized,
                latest.question if latest else None,
                dependency.dependent,
            )
            retrieval_question = heuristic_question
            reasons.extend(heuristic_reasons)

        routing_question = retrieval_question
        applied = retrieval_question != normalized
        result = RefinedQuery(
            original_question=original,
            normalized_question=normalized,
            routing_question=routing_question,
            retrieval_question=retrieval_question,
            applied=applied,
            used_llm=used_llm,
            reasons=reasons,
            context_dependency=dependency.trace(),
        )
        log_event(
            self._logger,
            "query_refined",
            original=original,
            normalized=normalized,
            routingQuestion=routing_question,
            retrievalQuestion=retrieval_question,
            applied=applied,
            usedLlm=used_llm,
            reasons=reasons,
            contextDependency=dependency.trace(),
        )
        return result

    def _llm_rewrite(self, question: str, state_card: str) -> str | None:
        if not self._llm_client.lightweight_enabled:
            return None
        prompt = f"【当前问题】{question}\n"
        if state_card:
            prompt += f"【会话状态卡】\n{state_card}\n"
        return self._llm_client.refine_query(prompt)


def heuristic_refine(
    question: str,
    previous_question: str | None,
    context_dependent: bool,
) -> tuple[str, list[str]]:
    refined = question
    reasons: list[str] = []

    standalone = build_follow_up_question(question, previous_question, context_dependent)
    if standalone != question:
        refined = standalone
        reasons.append("follow_up_completion")

    policy = normalize_policy_question(refined)
    if policy != refined:
        refined = policy
        reasons.append("policy_normalize")

    return refined, reasons


def normalize_question_text(question: str) -> str:
    normalized = question.strip()
    for filler in LEADING_FILLERS:
        if normalized.startswith(filler):
            normalized = normalized[len(filler):].strip()
            break
    for source, target in COLLOQUIAL_REPLACEMENTS:
        normalized = normalized.replace(source, target)
    normalized = normalized.replace("么", "么")
    normalized = re.sub(r"\s+", " ", normalized)
    return normalized.strip("，。；、 ")


def build_follow_up_question(question: str, previous_question: str | None, context_dependent: bool) -> str:
    if not previous_question or not context_dependent:
        return question
    if any(keyword in question for keyword in ("再具体一点", "具体一点", "详细一点", "展开讲讲", "再详细一点")):
        return f"{previous_question}，请再具体说明。"
    if question.startswith("那") or question.startswith("这个") or question.startswith("这"):
        return f"{previous_question}。补充问题：{question}"
    return f"{previous_question}。{question}"


def normalize_policy_question(question: str) -> str:
    if not is_policy_like(question):
        return question
    normalized = question
    normalized = normalized.replace("请分别说明", "分别说明")
    normalized = normalized.replace("请对比说明", "对比说明")
    normalized = normalized.replace("请说明", "说明")
    normalized = normalized.replace("怎么办理规则", "办理规则")
    normalized = re.sub(r"[?？]+$", "", normalized)
    return normalized.strip()


def is_policy_like(question: str) -> bool:
    return any(keyword in question for keyword in POLICY_KEYWORDS) or any(keyword in question for keyword in POLICY_INTENT_HINTS)


def should_use_llm_refiner(question: str) -> bool:
    return is_policy_like(question)
