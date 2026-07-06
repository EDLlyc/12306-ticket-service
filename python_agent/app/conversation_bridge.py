from __future__ import annotations

from dataclasses import dataclass

from .context_classifier import ContextDependencyClassifier, is_short_follow_up
from .memory_store import SessionMemoryStore
from .topic_switch import is_explicit_topic_switch


@dataclass(frozen=True)
class BridgeResult:
    applied: bool
    question: str
    reason: str
    context_dependency: dict


class ConversationBridge:
    def __init__(self, memory_store: SessionMemoryStore, context_classifier: ContextDependencyClassifier) -> None:
        self._memory_store = memory_store
        self._context_classifier = context_classifier

    def bridge(self, session_id: str, question: str, intent: str) -> BridgeResult:
        normalized = question.strip()
        latest = self._memory_store.latest(session_id)
        if latest is not None and is_explicit_topic_switch(normalized, latest.question, latest.intent):
            decision = self._context_classifier.classify(normalized, latest, prefer_llm=False)
            return BridgeResult(False, normalized, "topic_switched", decision.trace())
        decision = self._context_classifier.classify(normalized, latest, prefer_llm=False)
        if latest is None or not decision.dependent:
            return BridgeResult(False, normalized, decision.reason, decision.trace())
        bridged = f"上一轮问题：{latest.question}\n上一轮回答：{latest.answer}\n本轮追问：{normalized}"
        return BridgeResult(True, bridged, f"bridged_from_{latest.intent}", decision.trace())
