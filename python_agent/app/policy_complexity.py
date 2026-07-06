from __future__ import annotations

from dataclasses import dataclass

from .intent_router import POLICY_KEYWORDS
from .llm_client import ZhipuLLMClient
from .logging_utils import get_logger, log_event


COMPLEX_CONNECTORS = ("分别", "同时", "以及", "并且", "区别", "对比", "或者", "还是", "另外", "补充说明")


@dataclass(frozen=True)
class PolicyComplexityDecision:
    complex: bool
    confidence: float
    reason: str
    source: str
    signals: dict[str, int]

    def trace(self) -> dict:
        return {
            "complex": self.complex,
            "confidence": self.confidence,
            "reason": self.reason,
            "source": self.source,
            "signals": self.signals,
        }


class PolicyComplexityClassifier:
    def __init__(self, llm_client: ZhipuLLMClient) -> None:
        self._llm_client = llm_client
        self._logger = get_logger("python_agent.policy_complexity")

    def classify(self, question: str) -> PolicyComplexityDecision:
        normalized = question.strip()
        heuristic = heuristic_policy_complexity(normalized)
        decision = heuristic
        if should_use_llm_policy_complexity(normalized, heuristic, self._llm_client):
            llm_decision = self._llm_classify(normalized)
            if llm_decision is not None:
                decision = llm_decision
        log_event(self._logger, "policy_complexity_classified", question=normalized, trace=decision.trace())
        return decision

    def _llm_classify(self, question: str) -> PolicyComplexityDecision | None:
        payload = self._llm_client.classify_policy_complexity(question=question)
        if payload is None:
            return None
        is_complex = bool(payload.get("complex"))
        reason = str(payload.get("reason") or ("llm_complex" if is_complex else "llm_simple"))
        return PolicyComplexityDecision(
            complex=is_complex,
            confidence=0.78,
            reason=reason,
            source="llm",
            signals={},
        )


def heuristic_policy_complexity(question: str) -> PolicyComplexityDecision:
    normalized = question.strip()
    connector_hits = sum(1 for token in COMPLEX_CONNECTORS if token in normalized)
    policy_hits = len({keyword for keyword in POLICY_KEYWORDS if keyword in normalized})
    punctuation_splits = sum(1 for token in ("，", "；", "。", "、") if token in normalized)
    signals = {
        "connectorHits": connector_hits,
        "policyHits": policy_hits,
        "punctuationSplits": punctuation_splits,
        "length": len(normalized),
    }
    if connector_hits >= 1 and len(normalized) >= 24:
        return PolicyComplexityDecision(True, 0.92, "multi_clause_connectors", "heuristic", signals)
    if policy_hits >= 2:
        return PolicyComplexityDecision(True, 0.88, "multi_policy_topics", "heuristic", signals)
    if len(normalized) >= 30 and punctuation_splits >= 1:
        return PolicyComplexityDecision(True, 0.74, "long_multi_clause", "heuristic", signals)
    return PolicyComplexityDecision(False, 0.86, "single_topic", "heuristic", signals)


def should_use_llm_policy_complexity(
    question: str,
    heuristic: PolicyComplexityDecision,
    llm_client: ZhipuLLMClient,
) -> bool:
    if not getattr(llm_client, "lightweight_enabled", False):
        return False
    if heuristic.complex and heuristic.confidence >= 0.88:
        return False
    return 16 <= len(question) <= 40
