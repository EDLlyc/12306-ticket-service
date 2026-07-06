from .action_models import ActionPlan, AgentResult, StepStatus, StepType, ToolObservation
from .confirmation_store import ConfirmationStore, InMemoryConfirmationStore, confirmation_prompt, is_cancel_message, is_confirm_message, pending_to_step
from .llm_planner import LLMActionPlanner
from .planner import ActionPlanner
from .response_summarizer import ResponseSummarizer
from .schemas import AskRequest
from .stream_events import StreamEventCallback, emit_stream_event
from .tool_executor import ToolExecutor


class PythonActionAgentService:
    def __init__(
        self,
        planner: ActionPlanner,
        executor: ToolExecutor,
        llm_planner: LLMActionPlanner | None = None,
        summarizer: ResponseSummarizer | None = None,
        confirmation_store: ConfirmationStore | None = None,
        confirmation_required: bool = True,
    ) -> None:
        self._planner = planner
        self._executor = executor
        self._llm_planner = llm_planner
        self._summarizer = summarizer
        self._confirmation_store = confirmation_store or InMemoryConfirmationStore()
        self._confirmation_required = confirmation_required
        self._max_plan_steps = 4
        self._max_replan_times = 3
        self._side_effect_tools = {"book_ticket", "book_ticket_by_route", "refund_order", "refund_order_by_train"}

    async def answer(self, request: AskRequest, event_callback: StreamEventCallback | None = None) -> AgentResult:
        pending_result = await self.handle_pending_confirmation(request, event_callback)
        if pending_result is not None:
            return pending_result

        plan = self.build_plan(request)
        if plan is None:
            return AgentResult("当前信息不足，请补充后再继续。", self.build_fallback_plan("当前信息不足，请补充后再继续。"), None)
        emit_stream_event(
            event_callback,
            "plan_generated",
            {
                "goal": plan.goal,
                "replanned": False,
                "replanCount": 0,
                "fallbackUsed": False,
                "stepCount": len(plan.steps),
                "steps": [serialize_step_outline(step) for step in plan.steps],
            },
        )

        replanned = False
        replan_reasons: list[str] = []
        for _ in range(self._max_replan_times + 1):
            answer, final_observation, need_replan, replan_reason = await self._run_plan(plan, request, event_callback)
            if not need_replan:
                return AgentResult(
                    answer,
                    plan,
                    final_observation,
                    replanned=replanned,
                    fallback_used=False,
                    replan_count=len(replan_reasons),
                    replan_reason_history=tuple(replan_reasons),
                )
            if replan_reason:
                replan_reasons.append(replan_reason)
                emit_stream_event(
                    event_callback,
                    "replan_triggered",
                    {
                        "reason": replan_reason,
                        "replanCount": len(replan_reasons),
                        "goal": plan.goal,
                    },
                )
            replanned_plan = self.build_plan(request, previous_plan=plan, replan_reason=replan_reason)
            if replanned_plan is None:
                break
            plan = replanned_plan
            replanned = True
            emit_stream_event(
                event_callback,
                "plan_generated",
                {
                    "goal": plan.goal,
                    "replanned": True,
                    "replanCount": len(replan_reasons),
                    "fallbackUsed": False,
                    "stepCount": len(plan.steps),
                    "steps": [serialize_step_outline(step) for step in plan.steps],
                },
            )

        fallback = self.build_fallback_plan("抱歉，我暂时无法稳定生成执行计划，请稍后重试。")
        return AgentResult(
            "抱歉，我暂时无法稳定生成执行计划，请稍后重试。",
            fallback,
            ToolObservation(tool_name="respond", success=False, code="FALLBACK", message="抱歉，我暂时无法稳定生成执行计划，请稍后重试。"),
            replanned=replanned,
            fallback_used=True,
            replan_count=len(replan_reasons),
            replan_reason_history=tuple(replan_reasons),
        )

    async def _run_plan(
        self,
        plan,
        request: AskRequest,
        event_callback: StreamEventCallback | None = None,
        confirmation_bypass: bool = False,
    ) -> tuple[str, ToolObservation | None, bool, str | None]:
        final_observation: ToolObservation | None = None
        observations: list[ToolObservation] = []
        executed_side_effects: list[tuple[str, dict]] = []
        for index, step in enumerate(plan.steps):
            if step.type == StepType.RESPOND:
                step.status = StepStatus.SUCCESS
                message = self.build_respond_message(request, step, observations)
                final_observation = ToolObservation(tool_name="respond", success=True, code="RESPOND", message=message)
                step.observation = {"code": final_observation.code, "message": final_observation.message}
                step.observation_detail = build_observation_detail(final_observation, source="RESPOND")
                emit_stream_event(event_callback, "tool_executed", serialize_step_event(step))
                return final_observation.message, final_observation, False, None

            step.status = StepStatus.RUNNING
            duplicate = find_duplicate_side_effect(step, executed_side_effects, self._side_effect_tools)
            if duplicate is not None:
                duplicate_observation = ToolObservation(
                    tool_name=str(step.tool),
                    success=False,
                    code="DUPLICATE_SIDE_EFFECT_STEP",
                    message=f"检测到重复副作用步骤，已跳过：{duplicate}",
                    retryable=False,
                )
                step.status = StepStatus.FAILED
                step.observation = {
                    "toolName": duplicate_observation.tool_name,
                    "success": duplicate_observation.success,
                    "code": duplicate_observation.code,
                    "message": duplicate_observation.message,
                    "data": duplicate_observation.data,
                }
                step.observation_detail = build_observation_detail(duplicate_observation)
                emit_stream_event(event_callback, "tool_executed", serialize_step_event(step))
                return "", duplicate_observation, True, self.build_replan_reason(step.id, duplicate_observation, "计划重复安排了副作用步骤")
            observation = None if confirmation_bypass else self.build_confirmation_observation(step, request)
            if observation is not None:
                step.status = StepStatus.FAILED
                step.observation = {
                    "toolName": observation.tool_name,
                    "success": observation.success,
                    "code": observation.code,
                    "message": observation.message,
                    "data": observation.data,
                }
                step.observation_detail = build_observation_detail(observation, source="CONFIRMATION")
                emit_stream_event(event_callback, "tool_executed", serialize_step_event(step))
                self.skip_remaining_steps(plan.steps, index + 1)
                return observation.message, observation, False, None
            try:
                observation = await self.execute_step(step, request)
            except ValueError as exc:
                observation = ToolObservation(
                    tool_name=str(step.tool),
                    success=False,
                    code="INVALID_ARGS",
                    message=str(exc),
                    retryable=False,
                )
            step.status = StepStatus.SUCCESS if observation.success else StepStatus.FAILED
            step.observation = {
                "toolName": observation.tool_name,
                "success": observation.success,
                "code": observation.code,
                "message": observation.message,
                "data": observation.data,
            }
            step.observation_detail = build_observation_detail(observation)
            final_observation = observation
            observations.append(observation)
            emit_stream_event(event_callback, "tool_executed", serialize_step_event(step))
            if step.tool is not None and str(step.tool) in self._side_effect_tools and observation.success:
                executed_side_effects.append((str(step.tool), dict(step.args or {})))
            if observation.code == "UNKNOWN_TOOL":
                return "", observation, True, self.build_replan_reason(step.id, observation, "执行器返回未知工具")
            if not observation.message:
                return "", observation, True, self.build_replan_reason(step.id, observation, "执行后没有返回可展示结果")
            if observation.terminal:
                self.skip_remaining_steps(plan.steps, index + 1)
                return observation.message, observation, False, None

        fallback = final_observation.message if final_observation else "抱歉，当前没有可执行结果。"
        return fallback, final_observation, False, None

    @property
    def max_replan_times(self) -> int:
        return self._max_replan_times

    @property
    def side_effect_tools(self) -> set[str]:
        return set(self._side_effect_tools)

    def build_confirmation_observation(self, step, request: AskRequest) -> ToolObservation | None:
        if not self._requires_confirmation(step):
            return None
        if not request.token and not request.username:
            return None
        pending = self._confirmation_store.put(request.session_id, step)
        return ToolObservation(
            tool_name=str(step.tool),
            success=False,
            code="CONFIRM_REQUIRED",
            message=confirmation_prompt(pending),
            data={
                "pendingTool": pending.tool,
                "pendingArgs": pending.args,
                "confirmRequired": True,
            },
            terminal=True,
            retryable=False,
        )

    async def handle_pending_confirmation(
        self,
        request: AskRequest,
        event_callback: StreamEventCallback | None = None,
    ) -> AgentResult | None:
        pending = self._confirmation_store.get(request.session_id)
        if pending is None:
            return None
        if is_cancel_message(request.question):
            pending = self._confirmation_store.pop(request.session_id)
            message = "已取消该高风险操作。"
            plan = self.build_fallback_plan(message)
            observation = ToolObservation(
                tool_name="confirmation",
                success=True,
                code="CONFIRM_CANCELLED",
                message=message,
                data={"pendingTool": pending.tool if pending else None},
            )
            return AgentResult(message, plan, observation)
        if not is_confirm_message(request.question):
            message = confirmation_prompt(pending)
            plan = self.build_fallback_plan(message)
            observation = ToolObservation(
                tool_name="confirmation",
                success=False,
                code="CONFIRM_STILL_REQUIRED",
                message=message,
                data={"pendingTool": pending.tool, "pendingArgs": pending.args, "confirmRequired": True},
            )
            return AgentResult(message, plan, observation)

        pending = self._confirmation_store.pop(request.session_id)
        if pending is None:
            return None
        step = pending_to_step(pending)
        plan = self.normalize_plan(ActionPlan(goal=f"confirmed_{pending.tool}", steps=[step]))
        assert plan is not None
        emit_stream_event(
            event_callback,
            "confirmation_accepted",
            {"tool": pending.tool, "args": pending.args},
        )
        answer, final_observation, need_replan, replan_reason = await self._run_plan(
            plan,
            request,
            event_callback,
            confirmation_bypass=True,
        )
        if need_replan:
            observation = final_observation or ToolObservation(
                tool_name=pending.tool,
                success=False,
                code="CONFIRMED_ACTION_FAILED",
                message=replan_reason or "确认后的操作执行失败。",
            )
            return AgentResult(observation.message, plan, observation, replanned=False, fallback_used=False)
        return AgentResult(answer, plan, final_observation)

    def _requires_confirmation(self, step) -> bool:
        return self._confirmation_required and step.tool is not None and str(step.tool) in self._side_effect_tools

    def build_plan(self, request: AskRequest, previous_plan=None, replan_reason: str | None = None):
        if self._llm_planner and self._llm_planner.enabled:
            llm_plan = self._llm_planner.build_plan(
                username=request.username,
                original_question=request.question,
                safe_question=request.question,
                previous_plan=previous_plan,
                replan_reason=replan_reason,
            )
            normalized = self.normalize_plan(llm_plan)
            if normalized is not None:
                return normalized
        return self.normalize_plan(self._planner.build_plan(request))

    def normalize_plan(self, plan):
        if plan is None or not plan.steps:
            return None

        steps = [step for step in plan.steps if step is not None][: self._max_plan_steps]
        if not steps:
            return None
        for index, step in enumerate(steps, start=1):
            if not step.id:
                step.id = f"s{index}"
            step.status = StepStatus.PENDING
            if step.type == StepType.TOOL and step.tool is None:
                step.type = StepType.RESPOND
                step.instruction = step.instruction or "请补充当前操作需要的关键信息。"
        if steps[-1].type != StepType.RESPOND:
            from .action_models import ActionStep

            steps.append(
                ActionStep(
                    id=f"s{len(steps) + 1}",
                    type=StepType.RESPOND,
                    instruction="基于已完成步骤，直接告诉用户可见结果。",
                    status=StepStatus.PENDING,
                )
            )
        plan.steps = steps
        return plan

    def skip_remaining_steps(self, steps, start_index: int) -> None:
        for step in steps[start_index:]:
            if step.status == StepStatus.PENDING:
                step.status = StepStatus.SKIPPED

    def build_replan_reason(self, step_id: str, observation: ToolObservation | None, fallback_reason: str) -> str:
        reason = f"步骤 {step_id} 执行异常：{fallback_reason}"
        if observation is not None:
            reason += f"；tool={observation.tool_name}"
            if observation.code:
                reason += f"；code={observation.code}"
            if observation.message:
                reason += f"；message={observation.message}"
            if observation.retryable:
                reason += "；retryable=true"
        return reason

    def build_fallback_plan(self, instruction: str):
        from .action_models import ActionPlan, ActionStep

        return ActionPlan(
            goal="fallback_to_react_agent",
            steps=[
                ActionStep(
                    id="fallback",
                    type=StepType.RESPOND,
                    instruction=instruction,
                    status=StepStatus.PENDING,
                )
            ],
        )

    def build_respond_message(
        self,
        request: AskRequest,
        respond_step,
        observations: list[ToolObservation],
    ) -> str:
        if self._summarizer is None:
            return observations[-1].message if observations else (respond_step.instruction or "抱歉，我暂时无法完成这次操作。")
        return self._summarizer.summarize(
            original_question=request.question,
            respond_step=respond_step,
            observations=observations,
        )

    async def execute_step(self, step, request: AskRequest) -> ToolObservation:
        return await self._executor.execute(step, request)


def build_observation_detail(observation: ToolObservation | None, source: str = "TOOL") -> dict:
    if observation is None:
        return {
            "source": source,
            "success": False,
            "code": "TOOL_EXECUTION_EMPTY",
            "message": "tool_execution_returned_null",
            "data": {},
            "terminal": True,
            "retryable": False,
        }
    return {
        "source": source,
        "toolName": observation.tool_name,
        "success": observation.success,
        "code": observation.code,
        "message": observation.message,
        "data": observation.data or {},
        "terminal": observation.terminal,
        "retryable": observation.retryable,
    }


def find_duplicate_side_effect(step, executed_side_effects: list[tuple[str, dict]], side_effect_tools: set[str]) -> str | None:
    tool_name = str(step.tool) if step.tool is not None else ""
    if tool_name not in side_effect_tools:
        return None
    current_args = step.args or {}
    for previous_tool_name, previous_args in executed_side_effects:
        if previous_tool_name != tool_name:
            continue
        if arguments_overlap(current_args, previous_args):
            return tool_name
    return None


def arguments_overlap(current_args: dict, previous_data: dict) -> bool:
    if not current_args:
        return True
    for key, value in current_args.items():
        if value is None:
            continue
        if key in previous_data and str(previous_data.get(key)) == str(value):
            return True
        camel_key = to_camel_case(key)
        if camel_key in previous_data and str(previous_data.get(camel_key)) == str(value):
            return True
    return False


def to_camel_case(key: str) -> str:
    parts = key.split("_")
    if len(parts) == 1:
        return key
    return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])


def serialize_step_outline(step) -> dict:
    return {
        "id": step.id,
        "type": str(step.type),
        "tool": None if step.tool is None else str(step.tool),
        "args": step.args,
        "instruction": step.instruction,
    }


def serialize_step_event(step) -> dict:
    return {
        "id": step.id,
        "type": str(step.type),
        "tool": None if step.tool is None else str(step.tool),
        "args": step.args,
        "instruction": step.instruction,
        "status": str(step.status),
        "observation": step.observation,
        "observationDetail": step.observation_detail,
    }
