from __future__ import annotations

from dataclasses import dataclass

from .llm_client import ZhipuLLMClient
from .logging_utils import get_logger, log_event


POLICY_KEYWORDS = (
    "政策", "规则", "规章", "规定", "学生票", "儿童票", "退票费", "改签", "报销", "候补", "实名", "行李", "宠物", "发票"
)


@dataclass(frozen=True)
class PolicyRouteDecision:
    policy: bool
    confidence: float
    source: str
    reason: str
    classifier: dict | None = None
    evidence: dict | None = None

    def trace(self) -> dict:
        return {
            "policy": self.policy,
            "confidence": self.confidence,
            "source": self.source,
            "reason": self.reason,
            "classifier": self.classifier,
            "evidence": self.evidence,
        }


class PolicyRouter:
    def __init__(self, llm_client: ZhipuLLMClient) -> None:
        self._llm_client = llm_client
        self._logger = get_logger("python_agent.policy_router")

    def decide(self, question: str, raw_contexts: list[str] | None = None) -> PolicyRouteDecision:
        normalized = question.strip()
        classifier = self._classify_question(normalized)
        evidence = self._judge_evidence(normalized, raw_contexts or [])
        decision = combine_policy_route(normalized, classifier, evidence)
        log_event(self._logger, "policy_route_decided", question=normalized, trace=decision.trace())
        return decision

    def _classify_question(self, question: str) -> dict | None:
        heuristic = heuristic_policy_classifier(question)
        if self._llm_client.lightweight_enabled and heuristic["label"] == "UNCERTAIN":
            payload = self._llm_client.classify_policy_question(question=question)
            if payload is not None:
                return payload
        return heuristic

    def _judge_evidence(self, question: str, raw_contexts: list[str]) -> dict | None:
        if not raw_contexts:
            return None
        heuristic = heuristic_policy_evidence(question, raw_contexts)
        if self._llm_client.lightweight_enabled and not heuristic["supported"]:
            payload = self._llm_client.judge_policy_evidence(question=question, raw_contexts=raw_contexts[:3])
            if payload is not None:
                return payload
        return heuristic


def heuristic_policy_classifier(question: str) -> dict:
    hits = sum(1 for keyword in POLICY_KEYWORDS if keyword in question)
    if hits >= 1:
        return {"label": "POLICY", "confidence": min(1.0, 0.62 + hits * 0.12), "reason": "policy_keyword_hit"}
    if any(token in question for token in ("买票", "查订单", "余票", "车次")):
        return {"label": "NON_POLICY", "confidence": 0.86, "reason": "action_ticket_keyword"}
    return {"label": "UNCERTAIN", "confidence": 0.5, "reason": "weak_signal"}


def heuristic_policy_evidence(question: str, raw_contexts: list[str]) -> dict:
    hits = 0
    for context in raw_contexts[:3]:
        if any(keyword in context for keyword in POLICY_KEYWORDS):
            hits += 1
    return {
        "supported": hits > 0,
        "confidence": min(1.0, 0.35 + hits * 0.2) if hits > 0 else 0.2,
        "reason": "context_policy_keyword_hit" if hits > 0 else "no_policy_context_signal",
    }


def combine_policy_route(question: str, classifier: dict | None, evidence: dict | None) -> PolicyRouteDecision:
    classifier_label = str((classifier or {}).get("label") or "UNCERTAIN").upper()
    classifier_confidence = float((classifier or {}).get("confidence") or 0.5)
    classifier_reason = str((classifier or {}).get("reason") or "unknown")
    evidence_supported = bool((evidence or {}).get("supported"))
    evidence_confidence = float((evidence or {}).get("confidence") or 0.0)
    evidence_reason = str((evidence or {}).get("reason") or "unknown")

    if classifier_label == "POLICY" and classifier_confidence >= 0.8:
        return PolicyRouteDecision(True, classifier_confidence, "policy_classifier_high", classifier_reason, classifier, evidence)
    if classifier_label == "NON_POLICY" and classifier_confidence >= 0.85:
        return PolicyRouteDecision(False, classifier_confidence, "policy_classifier_high", classifier_reason, classifier, evidence)
    if evidence_supported and evidence_confidence >= 0.7:
        return PolicyRouteDecision(True, evidence_confidence, "policy_evidence_gate", evidence_reason, classifier, evidence)
    if classifier_label == "POLICY" and classifier_confidence >= 0.65:
        return PolicyRouteDecision(True, classifier_confidence, "policy_classifier_fallback", classifier_reason, classifier, evidence)
    return PolicyRouteDecision(False, max(classifier_confidence, evidence_confidence), "policy_classifier_rejected", classifier_reason, classifier, evidence)
