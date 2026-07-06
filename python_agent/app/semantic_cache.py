from __future__ import annotations

from collections import OrderedDict
from dataclasses import asdict, dataclass
import json

from .logging_utils import get_logger, log_event


LOGGER = get_logger("python_agent.semantic_cache")

@dataclass(frozen=True)
class CacheEntry:
    normalized_question: str
    answer: str
    trace: dict


class SemanticCache:
    def __init__(self, limit: int) -> None:
        self._limit = limit
        self._entries: OrderedDict[str, CacheEntry] = OrderedDict()

    def get(self, question: str) -> CacheEntry | None:
        key = normalize_question(question)
        entry = self._entries.get(key)
        if entry is None:
            log_event(LOGGER, "semantic_cache_miss", backend="memory", key=key)
            return None
        self._entries.move_to_end(key)
        log_event(LOGGER, "semantic_cache_hit", backend="memory", key=key)
        return entry

    def put(self, question: str, answer: str, trace: dict) -> None:
        key = normalize_question(question)
        self._entries[key] = CacheEntry(normalized_question=key, answer=answer, trace=trace)
        self._entries.move_to_end(key)
        log_event(LOGGER, "semantic_cache_put", backend="memory", key=key)
        if len(self._entries) > self._limit:
            self._entries.popitem(last=False)

    def clear(self, question: str | None = None) -> int:
        if question is None:
            count = len(self._entries)
            self._entries.clear()
            log_event(LOGGER, "semantic_cache_clear", backend="memory", key="*", count=count)
            return count
        key = normalize_question(question)
        removed = 1 if self._entries.pop(key, None) is not None else 0
        log_event(LOGGER, "semantic_cache_clear", backend="memory", key=key, count=removed)
        return removed


class RedisBackedSemanticCache:
    def __init__(self, redis_client, limit: int, fallback: SemanticCache | None = None) -> None:
        self._redis = redis_client
        self._fallback = fallback or SemanticCache(limit)

    def get(self, question: str) -> CacheEntry | None:
        if self._redis is None:
            return self._fallback.get(question)
        key = self._key(question)
        payload = self._redis.get(key)
        if not payload:
            log_event(LOGGER, "semantic_cache_miss", backend="redis", key=key)
            return None
        data = json.loads(payload)
        log_event(LOGGER, "semantic_cache_hit", backend="redis", key=key)
        return CacheEntry(**data)

    def put(self, question: str, answer: str, trace: dict) -> None:
        if self._redis is None:
            self._fallback.put(question, answer, trace)
            return
        key = self._key(question)
        entry = CacheEntry(normalized_question=normalize_question(question), answer=answer, trace=trace)
        self._redis.set(key, json.dumps(asdict(entry), ensure_ascii=False))
        log_event(LOGGER, "semantic_cache_put", backend="redis", key=key)

    def clear(self, question: str | None = None) -> int:
        if self._redis is None:
            return self._fallback.clear(question)
        if question is not None:
            key = self._key(question)
            removed = int(self._redis.delete(key))
            log_event(LOGGER, "semantic_cache_clear", backend="redis", key=key, count=removed)
            return removed
        pattern = self._key("*")
        keys = list(self._redis.scan_iter(match=pattern))
        if not keys:
            log_event(LOGGER, "semantic_cache_clear", backend="redis", key="*", count=0)
            return 0
        removed = int(self._redis.delete(*keys))
        log_event(LOGGER, "semantic_cache_clear", backend="redis", key="*", count=removed)
        return removed

    @staticmethod
    def _key(question: str) -> str:
        return f"python_agent:semantic_cache:{normalize_question(question)}"


def normalize_question(question: str) -> str:
    return " ".join(question.strip().lower().split())
