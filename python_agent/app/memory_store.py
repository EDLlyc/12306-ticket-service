from __future__ import annotations

from collections import deque
from dataclasses import asdict, dataclass
import json
from typing import Protocol


@dataclass(frozen=True)
class MemoryTurn:
    question: str
    answer: str
    intent: str


@dataclass(frozen=True)
class SessionSnapshot:
    turns: list[MemoryTurn]
    summary: str | None = None


class MemoryStore(Protocol):
    def append(self, session_id: str, question: str, answer: str, intent: str) -> None: ...
    def recent(self, session_id: str) -> list[MemoryTurn]: ...
    def latest(self, session_id: str) -> MemoryTurn | None: ...
    def get_summary(self, session_id: str) -> str | None: ...
    def set_summary(self, session_id: str, summary: str) -> None: ...
    def clear(self, session_id: str) -> None: ...


class SessionMemoryStore:
    def __init__(self, limit: int) -> None:
        self._limit = limit
        self._sessions: dict[str, deque[MemoryTurn]] = {}
        self._summaries: dict[str, str] = {}

    def append(self, session_id: str, question: str, answer: str, intent: str) -> None:
        turns = self._sessions.setdefault(session_id, deque(maxlen=self._limit))
        turns.append(MemoryTurn(question=question, answer=answer, intent=intent))

    def recent(self, session_id: str) -> list[MemoryTurn]:
        turns = self._sessions.get(session_id)
        return list(turns) if turns else []

    def latest(self, session_id: str) -> MemoryTurn | None:
        turns = self._sessions.get(session_id)
        if not turns:
            return None
        return turns[-1]

    def get_summary(self, session_id: str) -> str | None:
        return self._summaries.get(session_id)

    def set_summary(self, session_id: str, summary: str) -> None:
        self._summaries[session_id] = summary

    def clear(self, session_id: str) -> None:
        self._sessions.pop(session_id, None)
        self._summaries.pop(session_id, None)


class RedisBackedSessionMemoryStore:
    def __init__(self, redis_client, limit: int, fallback: SessionMemoryStore | None = None) -> None:
        self._redis = redis_client
        self._limit = limit
        self._fallback = fallback or SessionMemoryStore(limit)

    def append(self, session_id: str, question: str, answer: str, intent: str) -> None:
        turn = MemoryTurn(question=question, answer=answer, intent=intent)
        if self._redis is None:
            self._fallback.append(session_id, question, answer, intent)
            return
        key = self._turns_key(session_id)
        self._redis.rpush(key, json.dumps(asdict(turn), ensure_ascii=False))
        self._redis.ltrim(key, -self._limit, -1)

    def recent(self, session_id: str) -> list[MemoryTurn]:
        if self._redis is None:
            return self._fallback.recent(session_id)
        raw_items = self._redis.lrange(self._turns_key(session_id), 0, -1)
        return [MemoryTurn(**json.loads(item)) for item in raw_items]

    def latest(self, session_id: str) -> MemoryTurn | None:
        recent = self.recent(session_id)
        return recent[-1] if recent else None

    def get_summary(self, session_id: str) -> str | None:
        if self._redis is None:
            return self._fallback.get_summary(session_id)
        return self._redis.get(self._summary_key(session_id))

    def set_summary(self, session_id: str, summary: str) -> None:
        if self._redis is None:
            self._fallback.set_summary(session_id, summary)
            return
        self._redis.set(self._summary_key(session_id), summary)

    def clear(self, session_id: str) -> None:
        if self._redis is None:
            self._fallback.clear(session_id)
            return
        self._redis.delete(self._turns_key(session_id))
        self._redis.delete(self._summary_key(session_id))

    @staticmethod
    def _turns_key(session_id: str) -> str:
        return f"python_agent:session:{session_id}:turns"

    @staticmethod
    def _summary_key(session_id: str) -> str:
        return f"python_agent:session:{session_id}:summary"
