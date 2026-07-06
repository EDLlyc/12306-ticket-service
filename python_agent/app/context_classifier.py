from __future__ import annotations

from dataclasses import dataclass

from .llm_client import ZhipuLLMClient
from .logging_utils import get_logger, log_event
from .memory_store import MemoryTurn


FOLLOW_UP_HINTS = (
    "再具体一点",
    "具体一点",
    "详细一点",
    "展开讲讲",
    "展开说说",
    "继续",
    "然后呢",
    "为什么",
    "什么意思",
    "再详细一点",
    "那这个呢",
    "这个呢",
    "这个规则呢",
)
REFERENCE_PREFIXES = ("那", "这", "这个", "那个", "这些", "那些", "它", "该")
REFERENCE_KEYWORDS = (
    "上面",
    "上一条",
    "上一轮",
    "前面",
    "刚才",
    "继续",
    "再说",
    "补充",
    "这个",
    "那个",
    "该",
)


@dataclass(frozen=True)
class ContextDependencyDecision:
    dependent: bool
    confidence: float
    reason: str
    source: str
    signals: list[str]

    def trace(self) -> dict:
        return {
            "dependent": self.dependent,
            "confidence": self.confidence,
            "reason": self.reason,
            "source": self.source,
            "signals": self.signals,
        }


class ContextDependencyClassifier:
    def __init__(self, llm_client: ZhipuLLMClient) -> None:
        self._llm_client = llm_client
        self._logger = get_logger("python_agent.context_classifier")

    def classify(
        self,
        question: str,
        latest_turn: MemoryTurn | None,
        *,
        prefer_llm: bool = True,
    ) -> ContextDependencyDecision:
        normalized = question.strip()
        if latest_turn is None:
            decision = ContextDependencyDecision(
                dependent=False,
                confidence=1.0,
                reason="no_previous_turn",
                source="heuristic",
                signals=[],
            )
            log_event(self._logger, "context_dependency_classified", question=normalized, latestTurn=False, trace=decision.trace())
            return decision

        heuristic = heuristic_context_dependency(normalized)
        decision = heuristic
        if prefer_llm and should_use_llm_context_classifier(normalized, heuristic, self._llm_client):
            llm_decision = self._llm_classify(normalized, latest_turn)
            if llm_decision is not None:
                decision = llm_decision

        log_event(
            self._logger,
            "context_dependency_classified",
            question=normalized,
            latestTurn=True,
            trace=decision.trace(),
        )
        return decision

    def _llm_classify(self, question: str, latest_turn: MemoryTurn) -> ContextDependencyDecision | None:
        payload = self._llm_client.classify_context_dependency(
            question=question,
            state_card=build_state_card(latest_turn.question, latest_turn.answer),
        )
        if payload is None:
            return None
        dependent = bool(payload.get("dependent"))
        reason = str(payload.get("reason") or ("llm_dependent" if dependent else "llm_standalone"))
        return ContextDependencyDecision(
            dependent=dependent,
            confidence=0.78,
            reason=reason,
            source="llm",
            signals=["llm_context_judgement"],
        )


def heuristic_context_dependency(question: str) -> ContextDependencyDecision:
    normalized = question.strip()
    signals: list[str] = []
    if is_short_follow_up(normalized):
        signals.append("short_follow_up")
    if normalized.startswith(REFERENCE_PREFIXES):
        signals.append("reference_prefix")
    if any(keyword in normalized for keyword in REFERENCE_KEYWORDS):
        signals.append("reference_keyword")
    if len(normalized) <= 10 and normalized.endswith(("呢", "吗", "呀", "啊")):
        signals.append("short_question")

    if signals:
        confidence = 0.9 if "short_follow_up" in signals else 0.76
        return ContextDependencyDecision(
            dependent=True,
            confidence=confidence,
            reason=signals[0],
            source="heuristic",
            signals=signals,
        )
    return ContextDependencyDecision(
        dependent=False,
        confidence=0.84,
        reason="standalone",
        source="heuristic",
        signals=[],
    )


def should_use_llm_context_classifier(
    question: str,
    heuristic: ContextDependencyDecision,
    llm_client: ZhipuLLMClient,
) -> bool:
    if not llm_client.lightweight_enabled:
        return False
    if len(question) <= 8 and (
        question.startswith(REFERENCE_PREFIXES) or any(keyword in question for keyword in ("这个", "那个", "该"))
    ):
        return True
    if heuristic.dependent and heuristic.confidence >= 0.85:
        return False
    return len(question) <= 18


def build_state_card(previous_question: str | None, previous_answer: str | None) -> str:
    if not previous_question:
        return ""
    answer = (previous_answer or "").strip()
    compact_answer = answer[:160] + ("..." if len(answer) > 160 else "")
    return f"上一轮问题：{previous_question}\n上一轮回答：{compact_answer}"


def is_short_follow_up(question: str) -> bool:
    if len(question) > 20:
        return False
    return any(keyword in question for keyword in FOLLOW_UP_HINTS)
