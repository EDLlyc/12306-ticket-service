from __future__ import annotations

TOPIC_POLICY_GENERIC = "policy_generic"


def extract_topic_labels(text: str) -> set[str]:
    normalized = (text or "").strip()
    if not normalized:
        return set()
    labels: set[str] = set()
    if "学生票" in normalized or "研究生" in normalized:
        labels.add("policy_student")
    if "儿童票" in normalized or "儿童乘车" in normalized or "免费儿童" in normalized:
        labels.add("policy_child")
    if any(keyword in normalized for keyword in ("退票", "退款", "退票费")):
        labels.add("action_refund")
    if "改签" in normalized or "变更到站" in normalized:
        labels.add("policy_change")
    if "军人" in normalized or "残疾军人" in normalized or "消防救援" in normalized:
        labels.add("policy_military")
    if "报销" in normalized or "发票" in normalized:
        labels.add("policy_invoice")
    if "候补" in normalized:
        labels.add("policy_waitlist")
    if "身份证" in normalized or "实名" in normalized or "证件" in normalized:
        labels.add("policy_identity")
    if "行李" in normalized:
        labels.add("policy_luggage")
    if "宠物" in normalized:
        labels.add("policy_pet")
    if any(keyword in normalized for keyword in ("买票", "购票", "订票", "预订", "预定")):
        labels.add("action_book")
    if any(token in normalized for token in ("G", "D", "C", "Z", "T", "K")) and any(char.isdigit() for char in normalized):
        labels.add("ticket_query")
    if any(keyword in normalized for keyword in ("订单", "order_sn", "订单号", "查订单")):
        labels.add("action_order")
    if any(keyword in normalized for keyword in ("余票", "票价", "时刻表", "车次", "列车", "发车", "到达")):
        labels.add("ticket_query")
    if not labels and any(keyword in normalized for keyword in ("政策", "规则", "规章", "规定")):
        labels.add(TOPIC_POLICY_GENERIC)
    return labels


def is_explicit_topic_switch(question: str, previous_question: str, previous_intent: str) -> bool:
    current_labels = {label for label in extract_topic_labels(question) if label != TOPIC_POLICY_GENERIC}
    if not current_labels:
        return False
    previous_labels = {label for label in extract_topic_labels(previous_question) if label != TOPIC_POLICY_GENERIC}
    if previous_labels and current_labels.isdisjoint(previous_labels):
        return True

    previous_intent_hint = (previous_intent or "").strip().upper()
    current_looks_action = any(label.startswith("action_") for label in current_labels)
    current_looks_ticket = "ticket_query" in current_labels
    current_looks_policy = any(label.startswith("policy_") for label in current_labels)
    if previous_intent_hint == "RAG" and (current_looks_action or current_looks_ticket):
        return True
    if previous_intent_hint == "ACTION" and (current_looks_policy or current_looks_ticket):
        return True
    if previous_intent_hint == "TICKET" and (current_looks_policy or current_looks_action):
        return True
    return False
