from __future__ import annotations

from .llm_client import ZhipuLLMClient
from .memory_store import MemoryStore


class SummaryMemoryService:
    def __init__(self, llm_client: ZhipuLLMClient, memory_store: MemoryStore) -> None:
        self._llm_client = llm_client
        self._memory_store = memory_store

    def refresh_summary_if_needed(self, session_id: str) -> str | None:
        turns = self._memory_store.recent(session_id)
        if len(turns) < 6:
            return self._memory_store.get_summary(session_id)

        summary = self._summarize(turns)
        self._memory_store.set_summary(session_id, summary)
        return summary

    def _summarize(self, turns) -> str:
        if self._llm_client.cloud_enabled:
            dialog = "\n".join(f"Q: {turn.question}\nA: {turn.answer}" for turn in turns[-8:])
            summary = self._llm_client.generate_text(
                system_prompt="你是对话摘要助手，请用简洁中文总结最近多轮对话中的用户需求、关键事实和未完成事项。",
                user_prompt=dialog,
                model=self._llm_client._settings.zhipu_policy_model,
            )
            if summary:
                return summary
        topics = [turn.question for turn in turns[-4:]]
        return "最近对话摘要：" + "；".join(topics)
