from __future__ import annotations

import json
from typing import Any

from .action_models import ActionPlan, ActionStep, StepType, StepStatus, ToolName
from .langchain_tools import tool_calling_schema
from .llm_client import ZhipuLLMClient
from .tool_catalog import build_tool_catalog_prompt


class LLMActionPlanner:
    def __init__(self, llm_client: ZhipuLLMClient) -> None:
        self._llm_client = llm_client

    @property
    def enabled(self) -> bool:
        return self._llm_client.cloud_enabled

    def build_plan(
        self,
        *,
        username: str | None,
        original_question: str,
        safe_question: str,
        previous_plan: ActionPlan | None = None,
        replan_reason: str | None = None,
    ) -> ActionPlan | None:
        if not self.enabled:
            return None

        tool_call_plan = self._build_plan_with_tool_schema(
            username=username,
            original_question=original_question,
            safe_question=safe_question,
            previous_plan=previous_plan,
            replan_reason=replan_reason,
        )
        if tool_call_plan is not None:
            return tool_call_plan

        system_prompt = """
你是 12306 动作规划器。
你的任务是为动作类请求生成最小可执行计划，只能输出 JSON，禁止输出 Markdown、解释或前后缀。
规则：
1. 固定 schema: {"goal":string,"steps":[...]}。
2. steps 最多 4 步，最后一步必须是 RESPOND。
3. step.type 只允许 TOOL 或 RESPOND。
4. TOOL step 必须包含 id、tool、args。
5. 优先使用业务闭环工具，不要拆成无意义多步。
6. 按路线买票优先用 book_ticket_by_route，不要先 search_trains 再 book_ticket。
7. 按车次退票优先用 refund_order_by_train。
8. 信息不足时直接生成 RESPOND step，要求用户补充信息。
9. 不要重复安排带副作用的工具步骤。
10. 当前登录用户名由执行器注入，args 里不需要传 username。
可用工具如下：
""".strip() + "\n" + build_tool_catalog_prompt()

        user_prompt = (
            f"【当前登录用户】{username or ''}\n"
            f"【用户原始问题】{original_question}\n"
            f"【安全上下文】{safe_question}\n"
        )
        if previous_plan is not None and replan_reason:
            user_prompt += (
                f"【上一版计划】{json.dumps(serialize_plan(previous_plan), ensure_ascii=False)}\n"
                f"【重规划原因】{replan_reason}\n"
                "请只重写剩余计划，不要重复已完成且可能产生副作用的步骤。\n"
            )
        else:
            user_prompt += "请只输出执行计划 JSON。"
        raw_plan = self._llm_client.generate_agent_text(system_prompt, user_prompt)
        return parse_plan(raw_plan)

    def _build_plan_with_tool_schema(
        self,
        *,
        username: str | None,
        original_question: str,
        safe_question: str,
        previous_plan: ActionPlan | None = None,
        replan_reason: str | None = None,
    ) -> ActionPlan | None:
        system_prompt = """
你是 12306 动作规划器。
请基于用户请求选择一个最合适的工具调用；如果信息不足，不要调用工具。
规则：
1. 查询类请求选择查询工具。
2. 按路线买票优先选择 book_ticket_by_route。
3. 按车次退票优先选择 refund_order_by_train。
4. 当前登录用户名由执行器注入，工具参数里不要传 username。
5. 不要重复调用带副作用的工具。
""".strip()
        user_prompt = (
            f"【当前登录用户】{username or ''}\n"
            f"【用户原始问题】{original_question}\n"
            f"【安全上下文】{safe_question}\n"
        )
        if previous_plan is not None and replan_reason:
            user_prompt += (
                f"【上一版计划】{json.dumps(serialize_plan(previous_plan), ensure_ascii=False)}\n"
                f"【重规划原因】{replan_reason}\n"
                "请只规划剩余动作，不要重复已完成且可能产生副作用的步骤。\n"
            )
        calls = self._llm_client.generate_agent_tool_calls(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            tools=tool_calling_schema(low_risk_only=False),
            tool_choice="auto",
        )
        return plan_from_tool_calls(calls, original_question)


def parse_plan(raw_plan: str | None) -> ActionPlan | None:
    if raw_plan is None or not raw_plan.strip():
        return None
    extracted = extract_json(raw_plan)
    if extracted is None:
        return None
    try:
        payload = json.loads(extracted)
    except json.JSONDecodeError:
        return None

    goal = str(payload.get("goal") or "完成当前 12306 动作请求")
    raw_steps = payload.get("steps")
    if not isinstance(raw_steps, list) or not raw_steps:
        return None

    steps: list[ActionStep] = []
    for index, raw_step in enumerate(raw_steps[:4], start=1):
        if not isinstance(raw_step, dict):
            continue
        step_type = parse_step_type(raw_step.get("type"), raw_step.get("tool"))
        tool = parse_tool(raw_step.get("tool")) if step_type == StepType.TOOL else None
        args = raw_step.get("args") if isinstance(raw_step.get("args"), dict) else {}
        steps.append(
            ActionStep(
                id=str(raw_step.get("id") or f"s{index}"),
                type=step_type,
                tool=tool,
                args=args,
                instruction=str(raw_step.get("instruction") or raw_step.get("message") or ""),
                status=StepStatus.PENDING,
            )
        )

    if not steps:
        return None
    if steps[-1].type != StepType.RESPOND:
        steps.append(
            ActionStep(
                id=f"s{len(steps) + 1}",
                type=StepType.RESPOND,
                instruction="基于已完成步骤，直接告诉用户可见结果。",
                status=StepStatus.PENDING,
            )
        )
    return ActionPlan(goal=goal, steps=steps)


def plan_from_tool_calls(calls: list[dict[str, Any]] | None, question: str) -> ActionPlan | None:
    if not calls:
        return None

    steps: list[ActionStep] = []
    for index, call in enumerate(calls[:3], start=1):
        if not isinstance(call, dict):
            continue
        tool = parse_tool(call.get("name"))
        if tool is None:
            continue
        args = call.get("args") if isinstance(call.get("args"), dict) else {}
        steps.append(
            ActionStep(
                id=f"s{index}",
                type=StepType.TOOL,
                tool=tool,
                args=args,
                instruction=f"调用工具 {tool}",
                status=StepStatus.PENDING,
            )
        )
    if not steps:
        return None
    steps.append(
        ActionStep(
            id=f"s{len(steps) + 1}",
            type=StepType.RESPOND,
            instruction="基于工具执行结果，直接告诉用户可见结果。",
            status=StepStatus.PENDING,
        )
    )
    return ActionPlan(goal=f"完成动作请求：{question}", steps=steps)


def extract_json(raw_plan: str) -> str | None:
    trimmed = raw_plan.strip()
    if trimmed.startswith("```"):
        trimmed = trimmed.replace("```json", "").replace("```", "").strip()
    start = trimmed.find("{")
    end = trimmed.rfind("}")
    if start < 0 or end <= start:
        return None
    return trimmed[start : end + 1]


def parse_step_type(raw_type: Any, raw_tool: Any) -> StepType:
    value = str(raw_type or "").strip().upper()
    if value == StepType.RESPOND:
        return StepType.RESPOND
    if value == StepType.TOOL:
        return StepType.TOOL
    return StepType.TOOL if raw_tool else StepType.RESPOND


def parse_tool(raw_tool: Any) -> ToolName | None:
    if raw_tool is None:
        return None
    normalized = str(raw_tool).strip().lower()
    try:
        return ToolName(normalized)
    except ValueError:
        return None


def serialize_plan(plan: ActionPlan) -> dict[str, Any]:
    return {
        "goal": plan.goal,
        "steps": [
            {
                "id": step.id,
                "type": step.type,
                "tool": step.tool,
                "args": step.args,
                "status": step.status,
                "observation": step.observation,
                "observationDetail": step.observation_detail,
            }
            for step in plan.steps
        ],
    }
