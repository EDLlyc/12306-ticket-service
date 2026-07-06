from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from time import time
from typing import Any, Protocol

from .action_models import ActionStep, StepStatus, StepType, ToolName


@dataclass(frozen=True)
class PendingConfirmation:
    session_id: str
    tool: str
    args: dict[str, Any]
    instruction: str
    created_at: float


class ConfirmationStore(Protocol):
    def put(self, session_id: str, step: ActionStep) -> PendingConfirmation: ...
    def get(self, session_id: str) -> PendingConfirmation | None: ...
    def pop(self, session_id: str) -> PendingConfirmation | None: ...
    def clear(self, session_id: str) -> None: ...


class InMemoryConfirmationStore:
    def __init__(self) -> None:
        self._items: dict[str, PendingConfirmation] = {}

    def put(self, session_id: str, step: ActionStep) -> PendingConfirmation:
        pending = PendingConfirmation(
            session_id=session_id,
            tool=str(step.tool),
            args=dict(step.args or {}),
            instruction=step.instruction,
            created_at=time(),
        )
        self._items[session_id] = pending
        return pending

    def get(self, session_id: str) -> PendingConfirmation | None:
        return self._items.get(session_id)

    def pop(self, session_id: str) -> PendingConfirmation | None:
        return self._items.pop(session_id, None)

    def clear(self, session_id: str) -> None:
        self._items.pop(session_id, None)


class RedisBackedConfirmationStore:
    def __init__(self, redis_client, fallback: ConfirmationStore | None = None, ttl_seconds: int = 300) -> None:
        self._redis = redis_client
        self._fallback = fallback or InMemoryConfirmationStore()
        self._ttl_seconds = ttl_seconds

    def put(self, session_id: str, step: ActionStep) -> PendingConfirmation:
        pending = PendingConfirmation(
            session_id=session_id,
            tool=str(step.tool),
            args=dict(step.args or {}),
            instruction=step.instruction,
            created_at=time(),
        )
        if self._redis is None:
            return self._fallback.put(session_id, step)
        self._redis.set(self._key(session_id), json.dumps(asdict(pending), ensure_ascii=False), ex=self._ttl_seconds)
        return pending

    def get(self, session_id: str) -> PendingConfirmation | None:
        if self._redis is None:
            return self._fallback.get(session_id)
        raw = self._redis.get(self._key(session_id))
        if not raw:
            return None
        return PendingConfirmation(**json.loads(raw))

    def pop(self, session_id: str) -> PendingConfirmation | None:
        if self._redis is None:
            return self._fallback.pop(session_id)
        pending = self.get(session_id)
        self._redis.delete(self._key(session_id))
        return pending

    def clear(self, session_id: str) -> None:
        if self._redis is None:
            self._fallback.clear(session_id)
            return
        self._redis.delete(self._key(session_id))

    @staticmethod
    def _key(session_id: str) -> str:
        return f"python_agent:confirmation:{session_id}"


def pending_to_step(pending: PendingConfirmation) -> ActionStep:
    return ActionStep(
        id="confirmed_step",
        type=StepType.TOOL,
        instruction=pending.instruction,
        tool=ToolName(pending.tool),
        args=dict(pending.args),
        status=StepStatus.PENDING,
    )


def is_confirm_message(text: str) -> bool:
    normalized = text.strip().lower()
    return normalized in {"确认", "确定", "是", "yes", "y", "ok", "okay", "执行", "继续"}


def is_cancel_message(text: str) -> bool:
    normalized = text.strip().lower()
    return normalized in {"取消", "算了", "不要", "否", "no", "n", "stop", "停止"}


def confirmation_prompt(pending: PendingConfirmation) -> str:
    tool_labels = {
        "book_ticket": "购票",
        "book_ticket_by_route": "按路线购票",
        "refund_order": "退票",
        "refund_order_by_train": "按车次退票",
    }
    label = tool_labels.get(pending.tool, pending.tool)
    args = "，".join(f"{key}: {value}" for key, value in pending.args.items()) or "无参数"
    return f"该操作属于高风险动作：{label}（{args}）。请回复“确认”后我再执行，或回复“取消”放弃。"
