from __future__ import annotations

from dataclasses import dataclass

from .context_classifier import ContextDependencyDecision
from .intent_router import ACTION_KEYWORDS, CHITCHAT_KEYWORDS, POLICY_KEYWORDS, TICKET_KEYWORDS
from .llm_client import ZhipuLLMClient
from .logging_utils import get_logger, log_event


@dataclass(frozen=True)
class IntentScoreDecision:
    recommended: str
    confidence: float
    scores: dict[str, float]
    source: str
    reason: str
    top_score: float
    second_score: float

    def trace(self) -> dict:
        return {
            "recommended": self.recommended,
            "confidence": self.confidence,
            "scores": self.scores,
            "source": self.source,
            "reason": self.reason,
            "topScore": self.top_score,
            "secondScore": self.second_score,
            "gap": max(0.0, self.top_score - self.second_score),
        }


class MultiIntentScorer:
    def __init__(self, llm_client: ZhipuLLMClient) -> None:
        self._llm_client = llm_client
        self._logger = get_logger("python_agent.intent_scorer")

    def score(self, question: str, context_dependency: ContextDependencyDecision | None = None) -> IntentScoreDecision:
        normalized = question.strip()
        heuristic = heuristic_score_intent(normalized, context_dependency)
        decision = heuristic
        if self._llm_client.lightweight_enabled and should_use_llm_scorer(normalized, heuristic):
            llm_decision = self._llm_score(normalized, context_dependency)
            if llm_decision is not None:
                decision = llm_decision
        log_event(self._logger, "intent_scored", question=normalized, trace=decision.trace())
        return decision

    def _llm_score(
        self,
        question: str,
        context_dependency: ContextDependencyDecision | None,
    ) -> IntentScoreDecision | None:
        payload = self._llm_client.score_intent(
            question=question,
            context_dependency=context_dependency.trace() if context_dependency is not None else None,
        )
        if payload is None:
            return None
        scores = {
            "ACTION": clamp_score(payload.get("actionScore")),
            "TICKET": clamp_score(payload.get("ticketScore")),
            "RAG": clamp_score(payload.get("policyScore")),
            "CHITCHAT": clamp_score(payload.get("chitchatScore")),
        }
        ranked = sorted(scores.items(), key=lambda item: item[1], reverse=True)
        top_score = ranked[0][1]
        second_score = ranked[1][1] if len(ranked) > 1 else 0.0
        return IntentScoreDecision(
            recommended=str(payload.get("recommended") or ranked[0][0]).strip().upper(),
            confidence=clamp_score(payload.get("confidence"), fallback=top_score),
            scores=scores,
            source="llm",
            reason=str(payload.get("reason") or "llm_intent_score"),
            top_score=top_score,
            second_score=second_score,
        )


def heuristic_score_intent(
    question: str,
    context_dependency: ContextDependencyDecision | None = None,
) -> IntentScoreDecision:
    scores = {
        "ACTION": keyword_score(question, ACTION_KEYWORDS),
        "TICKET": keyword_score(question, TICKET_KEYWORDS),
        "RAG": keyword_score(question, POLICY_KEYWORDS),
        "CHITCHAT": keyword_score(question, CHITCHAT_KEYWORDS),
    }
    if context_dependency is not None and context_dependency.dependent:
        scores["RAG"] = min(1.0, scores["RAG"] + 0.08)
    ranked = sorted(scores.items(), key=lambda item: item[1], reverse=True)
    top_intent, top_score = ranked[0]
    second_score = ranked[1][1] if len(ranked) > 1 else 0.0
    confidence = top_score if top_score > 0 else 0.0
    recommended = top_intent if top_score > 0 else "UNCERTAIN"
    reason = "keyword_scoring"
    return IntentScoreDecision(
        recommended=recommended,
        confidence=confidence,
        scores=scores,
        source="heuristic",
        reason=reason,
        top_score=top_score,
        second_score=second_score,
    )


def should_use_llm_scorer(question: str, heuristic: IntentScoreDecision) -> bool:
    gap = max(0.0, heuristic.top_score - heuristic.second_score)
    return heuristic.top_score <= 0.5 or gap < 0.18 or len(question) <= 18


def keyword_score(text: str, keywords: tuple[str, ...]) -> float:
    hits = sum(1 for keyword in keywords if keyword in text)
    if hits <= 0:
        return 0.0
    return min(1.0, 0.32 * hits + 0.13)


def clamp_score(value, fallback: float = 0.0) -> float:
    try:
        score = float(value)
    except Exception:
        score = fallback
    return max(0.0, min(1.0, score))
