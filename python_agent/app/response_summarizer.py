from __future__ import annotations

from .action_models import ActionStep, ToolObservation
from .llm_client import ZhipuLLMClient


class ResponseSummarizer:
    def __init__(self, llm_client: ZhipuLLMClient) -> None:
        self._llm_client = llm_client

    def summarize(
        self,
        *,
        original_question: str,
        respond_step: ActionStep,
        observations: list[ToolObservation],
    ) -> str:
        if not observations:
            return respond_step.instruction or "抱歉，我暂时无法完成这次操作。"
        if len(observations) == 1:
            return observations[0].message
        if not self._llm_client.cloud_enabled:
            return observations[-1].message

        system_prompt = """
你是 12306 动作执行总结器。
你必须严格基于已完成步骤的 observation 生成最终回答，禁止编造新的库存、订单号、车次或规则。
如果 observation 本身已经是完整面向用户的答复，请尽量直接复用。
输出简洁中文，不要解释内部规划过程。
""".strip()
        formatted = "\n".join(
            f"- tool={item.tool_name}, code={item.code}, success={item.success}, message={item.message}, data={item.data}"
            for item in observations
        )
        user_prompt = (
            f"【用户问题】{original_question}\n"
            f"【RESPOND 指令】{respond_step.instruction or ''}\n"
            f"【执行 observation】\n{formatted}"
        )
        answer = self._llm_client.generate_agent_text(system_prompt, user_prompt)
        return answer or observations[-1].message
