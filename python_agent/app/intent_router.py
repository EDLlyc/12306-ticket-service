from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class IntentLabel(StrEnum):
    ACTION = "ACTION"
    TICKET = "TICKET"
    RAG = "RAG"
    CHITCHAT = "CHITCHAT"
    UNCERTAIN = "UNCERTAIN"


ACTION_KEYWORDS = ("买票", "买", "购票", "订票", "订", "预订", "退票", "退款", "取消订单", "撤单", "查订单", "我的订单")
TICKET_KEYWORDS = ("余票", "票价", "时刻表", "车次", "列车", "发车", "到达", "查票", "天气", "适合", "出行建议", "坐高铁")
POLICY_KEYWORDS = (
    "政策", "规则", "规章", "规定", "学生票", "儿童票", "退票费", "改签", "报销", "候补", "实名", "行李", "宠物", "发票"
)
POLICY_INTENT_HINTS = (
    "要求",
    "规定",
    "规则",
    "条件",
    "流程",
    "区别",
    "对比",
    "分别",
    "说明",
    "怎么办",
    "怎么处理",
)
CHITCHAT_KEYWORDS = ("你好", "在吗", "谢谢", "辛苦了", "哈哈", "再见")


@dataclass(frozen=True)
class IntentDecision:
    intent: IntentLabel
    confidence: float
    scores: dict[str, float]
    reason: str


class IntentRouter:
    def __init__(self, high_confidence: float, margin_threshold: float) -> None:
        self._high_confidence = high_confidence
        self._margin_threshold = margin_threshold

    def route(self, question: str) -> IntentDecision:
        normalized = question.strip()
        scores = {
            IntentLabel.ACTION.value: score_keywords(normalized, ACTION_KEYWORDS),
            IntentLabel.TICKET.value: score_keywords(normalized, TICKET_KEYWORDS),
            IntentLabel.RAG.value: score_keywords(normalized, POLICY_KEYWORDS),
            IntentLabel.CHITCHAT.value: score_keywords(normalized, CHITCHAT_KEYWORDS),
        }
        scores = apply_policy_bias(normalized, scores)
        ranked = sorted(scores.items(), key=lambda item: item[1], reverse=True)
        top_label, top_score = ranked[0]
        second_score = ranked[1][1] if len(ranked) > 1 else 0.0
        if top_label in {IntentLabel.ACTION.value, IntentLabel.TICKET.value} and top_score >= 0.45:
            return IntentDecision(IntentLabel(top_label), top_score, scores, f"direct_{top_label.lower()}")
        if top_score <= 0:
            return IntentDecision(IntentLabel.UNCERTAIN, 0.0, scores, "no_signal")
        margin = top_score - second_score
        if top_score < self._high_confidence and margin < self._margin_threshold:
            return IntentDecision(IntentLabel.UNCERTAIN, top_score, scores, "low_margin")
        return IntentDecision(IntentLabel(top_label), min(top_score, 1.0), scores, f"top={top_label},margin={margin:.2f}")


def score_keywords(text: str, keywords: tuple[str, ...]) -> float:
    hit_count = sum(1 for keyword in keywords if keyword in text)
    if hit_count == 0:
        return 0.0
    return min(1.0, 0.45 * hit_count)


def apply_policy_bias(text: str, scores: dict[str, float]) -> dict[str, float]:
    policy_hits = sum(1 for keyword in POLICY_KEYWORDS if keyword in text)
    policy_hint_hits = sum(1 for keyword in POLICY_INTENT_HINTS if keyword in text)
    if policy_hits > 0 and policy_hint_hits > 0:
        boosted = dict(scores)
        boosted[IntentLabel.RAG.value] = min(1.0, boosted[IntentLabel.RAG.value] + 0.25)
        if "退票费" not in text and ("退票" in text or "退款" in text):
            boosted[IntentLabel.ACTION.value] = max(0.0, boosted[IntentLabel.ACTION.value] - 0.2)
        return boosted
    return scores
