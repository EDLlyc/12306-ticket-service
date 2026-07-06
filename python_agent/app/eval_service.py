from __future__ import annotations

from dataclasses import dataclass

from .memory_store import MemoryTurn, SessionMemoryStore
from .orchestrator import AgentOrchestrator
from .schemas import AskRequest


@dataclass(frozen=True)
class EvalReplayResult:
    session_id: str
    question: str
    with_memory_answer: str
    no_memory_answer: str
    contexts: list[str]
    metrics: dict
    processed_question: str
    no_memory_processed_question: str
    warmup_questions: list[str]
    with_memory_trace: dict
    no_memory_trace: dict


class EvalService:
    DEFAULT_WARMUP_QUESTION = "学生票资质核验有什么要求"

    def __init__(self, orchestrator: AgentOrchestrator, memory_store: SessionMemoryStore) -> None:
        self._orchestrator = orchestrator
        self._memory_store = memory_store

    async def ask_with_contexts(self, session_id: str, question: str, username: str) -> dict:
        result = await self._orchestrator.answer(AskRequest(session_id=session_id, question=question, username=username))
        trace = result.trace if isinstance(result.trace, dict) else {}
        return {
            "answer": result.answer,
            "contexts": trace.get("contexts") or [],
            "trace": trace,
        }

    async def replay_with_and_without_memory(
        self,
        session_id: str,
        question: str,
        username: str,
        warmup_questions: list[str] | None = None,
    ) -> EvalReplayResult:
        normalized_warmups = [item.strip() for item in (warmup_questions or []) if item and item.strip()]
        warmups_applied: list[str] = []
        if not self._memory_store.recent(session_id):
            warmups_applied = normalized_warmups or [self.DEFAULT_WARMUP_QUESTION]
            for warmup_question in warmups_applied:
                await self._orchestrator.answer(
                    AskRequest(
                        session_id=session_id,
                        question=warmup_question,
                        username=username,
                    )
                )
        with_memory = await self._orchestrator.answer(AskRequest(session_id=session_id, question=question, username=username))
        with_memory_trace = with_memory.trace if isinstance(with_memory.trace, dict) else {}
        snapshot = self._memory_store.recent(session_id)
        summary = self._memory_store.get_summary(session_id)
        self._memory_store.clear(session_id)
        no_memory = await self._orchestrator.answer(AskRequest(session_id=session_id, question=question, username=username))
        no_memory_trace = no_memory.trace if isinstance(no_memory.trace, dict) else {}
        self._memory_store.clear(session_id)
        self._restore_memory(session_id, snapshot, summary)

        diff_ratio = simple_diff_ratio(with_memory.answer, no_memory.answer)
        processed_question = str(with_memory_trace.get("effectiveQuestion") or question)
        no_memory_processed_question = str(no_memory_trace.get("effectiveQuestion") or question)
        summary_chars = len(summary or "")
        turns_chars = sum(len(turn.question) + len(turn.answer) for turn in snapshot)
        memory_block_chars = summary_chars + turns_chars
        return EvalReplayResult(
            session_id=session_id,
            question=question,
            with_memory_answer=with_memory.answer,
            no_memory_answer=no_memory.answer,
            contexts=with_memory_trace.get("contexts") or [],
            processed_question=processed_question,
            no_memory_processed_question=no_memory_processed_question,
            warmup_questions=warmups_applied,
            with_memory_trace=with_memory_trace,
            no_memory_trace=no_memory_trace,
            metrics={
                "answer_diff_ratio": diff_ratio,
                "same_answer": diff_ratio < 0.01,
                "with_memory_unknown": is_unknown_answer(with_memory.answer),
                "no_memory_unknown": is_unknown_answer(no_memory.answer),
                "with_memory_mode": with_memory_trace.get("mode"),
                "no_memory_mode": no_memory_trace.get("mode"),
                "contexts_count": len(with_memory_trace.get("contexts") or []),
                "memory_turn_count": len(snapshot),
                "memory_summary_chars": summary_chars,
                "memory_turn_chars": turns_chars,
                "memory_block_chars": memory_block_chars,
                "processed_question_changed": processed_question != no_memory_processed_question,
                "warmup_applied": bool(warmups_applied),
                "potential_memory_contamination": has_potential_memory_contamination(with_memory.answer, no_memory.answer, diff_ratio),
            },
        )

    def _restore_memory(self, session_id: str, snapshot: list[MemoryTurn], summary: str | None) -> None:
        for turn in snapshot:
            self._memory_store.append(session_id, turn.question, turn.answer, turn.intent)
        if summary:
            self._memory_store.set_summary(session_id, summary)


def normalize_replay_answer(answer: str) -> str:
    return (
        (answer or "")
        .strip()
        .replace("。", "")
        .replace("，", "")
        .replace("！", "")
        .replace("？", "")
        .replace(" ", "")
    )


def simple_diff_ratio(answer_a: str, answer_b: str) -> float:
    a = normalize_replay_answer(answer_a)
    b = normalize_replay_answer(answer_b)
    if not a and not b:
        return 0.0
    if a == b:
        return 0.0
    max_len = max(len(a), len(b))
    lcs = longest_common_subsequence_length(a, b)
    return 1.0 - float(lcs) / float(max_len or 1)


def longest_common_subsequence_length(a: str, b: str) -> int:
    if not a or not b:
        return 0
    prev = [0] * (len(b) + 1)
    curr = [0] * (len(b) + 1)
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            if a[i - 1] == b[j - 1]:
                curr[j] = prev[j - 1] + 1
            else:
                curr[j] = max(prev[j], curr[j - 1])
        prev, curr = curr, prev
    return prev[-1]


def is_unknown_answer(answer: str) -> bool:
    normalized = (answer or "").strip()
    if not normalized:
        return True
    return "未提及" in normalized or "没有检索到" in normalized


def has_potential_memory_contamination(with_memory_answer: str, no_memory_answer: str, diff_ratio: float) -> bool:
    if diff_ratio < 0.35:
        return False
    if not is_unknown_answer(with_memory_answer) and is_unknown_answer(no_memory_answer):
        return True
    return True
