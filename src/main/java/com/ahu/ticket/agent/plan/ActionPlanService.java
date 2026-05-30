package com.ahu.ticket.agent.plan;

import com.ahu.ticket.agent.ZhipuOfficialAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ActionPlanService {

    private static final int MAX_PLAN_STEPS = 4;
    private static final int MAX_REPLAN_TIMES = 5;

    private final ZhipuOfficialAgent zhipuOfficialAgent;
    private final ToolExecutionService toolExecutionService;
    private final ObjectMapper objectMapper;

    public ActionPlanService(ZhipuOfficialAgent zhipuOfficialAgent, ToolExecutionService toolExecutionService) {
        this.zhipuOfficialAgent = zhipuOfficialAgent;
        this.toolExecutionService = toolExecutionService;
        this.objectMapper = new ObjectMapper();
    }

    public ActionExecutionResult execute(String sessionId, String username, String originalQuestion, String safeQuestion) {
        try {
            ActionPlan plan = normalizePlan(buildPlan(username, originalQuestion, safeQuestion, null, null));
            boolean replanned = false;
            for (int attempt = 0; attempt <= MAX_REPLAN_TIMES; attempt++) {
                PlanRunOutcome outcome = runPlan(plan, username, originalQuestion);
                if (!outcome.needReplan()) {
                    return new ActionExecutionResult(outcome.finalAnswer(), plan, replanned, false);
                }
                ActionPlan replannedPlan = normalizePlan(
                        buildPlan(username, originalQuestion, safeQuestion, plan, outcome.replanReason())
                );
                if (replannedPlan.getSteps().isEmpty()) {
                    break;
                }
                plan = replannedPlan;
                replanned = true;
            }
        } catch (Exception e) {
            log.error("【PlanAndExecute】执行失败，回退旧 Agent。sessionId={}, question={}", sessionId, originalQuestion, e);
        }

        String fallbackAnswer = zhipuOfficialAgent.chat(sessionId, safeQuestion, username);
        ActionPlan fallbackPlan = new ActionPlan();
        fallbackPlan.setGoal("fallback_to_react_agent");
        ActionPlanStep step = new ActionPlanStep();
        step.setId("fallback");
        step.setType(ActionStepType.RESPOND);
        step.setStatus(ActionStepStatus.SUCCESS);
        step.setInstruction("回退到旧的 function-calling agent");
        recordRespondObservation(step, "FALLBACK", fallbackAnswer, Map.of("mode", "legacy_function_calling_agent"));
        fallbackPlan.setSteps(List.of(step));
        return new ActionExecutionResult(fallbackAnswer, fallbackPlan, false, true);
    }

    private ActionPlan buildPlan(String username, String originalQuestion, String safeQuestion,
                                 ActionPlan previousPlan, String replanReason) {
        String systemPrompt = """
                你是 12306 动作规划器（Plan-and-Execute Planner）。
                你的任务是为动作类请求生成一个最小可执行计划，只能输出 JSON，禁止输出 Markdown、解释或前后缀。
                你必须遵守以下规则：
                1. 输出 schema 固定为 {"goal":string,"steps":[...]}。
                2. steps 最多 4 步，且最后一步必须是 {"type":"RESPOND"}。
                3. step.type 只允许 TOOL 或 RESPOND。
                4. TOOL step 必须包含 id、tool、args。args 必须是 JSON object。
                5. 优先使用业务闭环工具，不要拆成无意义的多步。
                6. 用户按路线买票但没给车次时，优先用 bookTicketByRoute，不要先 searchTrainTickets 再 bookTicket。
                7. 用户按车次退票时，优先用 cancelOrderByTrainNumber。
                8. 如果信息不足或业务上需要用户补充，请不要伪造参数；直接生成一个 RESPOND step，让执行器向用户索取缺失信息。
                9. 不要重复安排带副作用的工具步骤，尤其是 bookTicket、cancelOrder、cancelAllOrders、cancelAllOrdersByTrainNumber。
                10. 当前登录用户名由执行器注入，args 里可以省略 username，也可以写成当前用户。
                11. 如果给了上一版计划和已执行 observation，请严格依据其中的 observationDetail.code、message、data 判断失败原因和已完成结果。
                可用工具如下：
                """ + buildToolCatalogPrompt();

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("【当前登录用户】").append(username).append("\n");
        userPrompt.append("【用户原始问题】").append(originalQuestion).append("\n");
        userPrompt.append("【安全上下文】\n").append(safeQuestion).append("\n");
        if (previousPlan != null && replanReason != null && !replanReason.isBlank()) {
            userPrompt.append("【上一版计划】\n").append(toCompactJson(previousPlan)).append("\n");
            String executedObservationContext = buildExecutedObservationContext(previousPlan);
            if (!executedObservationContext.isBlank()) {
                userPrompt.append("【已执行 observation】\n").append(executedObservationContext).append("\n");
            }
            userPrompt.append("【重规划原因】\n").append(replanReason).append("\n");
            userPrompt.append("请只重写剩余计划，不要重复已完成且可能产生副作用的步骤。\n");
        } else {
            userPrompt.append("请基于以上信息输出执行计划 JSON。\n");
        }

        String rawPlan = zhipuOfficialAgent.generateAgentText(systemPrompt, userPrompt.toString());
        return parsePlan(rawPlan);
    }

    private PlanRunOutcome runPlan(ActionPlan plan, String username, String originalQuestion) {
        List<ToolObservation> observations = new ArrayList<>();
        if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return new PlanRunOutcome("抱歉，当前未生成有效执行计划，请稍后重试。", false, null);
        }

        for (int index = 0; index < plan.getSteps().size(); index++) {
            ActionPlanStep step = plan.getSteps().get(index);
            if (step.getType() == ActionStepType.RESPOND) {
                step.setStatus(ActionStepStatus.RUNNING);
                String answer = buildFinalAnswer(originalQuestion, step, observations);
                recordRespondObservation(step, "RESPOND_SUCCESS", answer,
                        Map.of("observationCount", observations.size()));
                step.setStatus(ActionStepStatus.SUCCESS);
                return new PlanRunOutcome(answer, false, null);
            }

            if (step.getTool() == null || step.getTool().isBlank()) {
                step.setStatus(ActionStepStatus.FAILED);
                recordSystemObservation(step, "PLANNER_MISSING_TOOL_NAME", "planner_missing_tool_name",
                        Map.of("stepId", step.getId()), true, false);
                return new PlanRunOutcome(null, true, "步骤 " + step.getId() + " 缺少 tool 名称");
            }
            if (!zhipuOfficialAgent.supportsTool(step.getTool())) {
                step.setStatus(ActionStepStatus.FAILED);
                recordSystemObservation(step, "PLANNER_UNKNOWN_TOOL", "planner_unknown_tool",
                        Map.of("stepId", step.getId(), "tool", step.getTool()), true, false);
                return new PlanRunOutcome(null, true, "步骤 " + step.getId() + " 使用了未知工具: " + step.getTool());
            }

            step.setStatus(ActionStepStatus.RUNNING);
            Map<String, Object> args = step.getArgs() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(step.getArgs());
            ToolObservation observation = toolExecutionService.execute(step.getTool(), normalizeArgs(args, username), username);
            recordToolObservation(step, observation);
            String userMessage = observation == null ? null : observation.userMessage();
            if (userMessage == null || userMessage.isBlank()) {
                step.setStatus(ActionStepStatus.FAILED);
                return new PlanRunOutcome(null, true, buildReplanReason(step, observation, "执行后没有返回可展示结果"));
            }
            if (observation != null && "UNKNOWN_TOOL".equals(observation.code())) {
                step.setStatus(ActionStepStatus.FAILED);
                return new PlanRunOutcome(null, true, buildReplanReason(step, observation,
                        "执行器返回未知工具，请改用受支持工具"));
            }

            step.setStatus(observation.success() ? ActionStepStatus.SUCCESS : ActionStepStatus.FAILED);
            observations.add(observation);
            if (shouldShortCircuit(observation)) {
                skipRemainingSteps(plan.getSteps(), index + 1);
                return new PlanRunOutcome(userMessage, false, null);
            }
        }

        String finalAnswer = observations.isEmpty()
                ? "抱歉，当前没有可执行的动作结果。"
                : observations.get(observations.size() - 1).userMessage();
        return new PlanRunOutcome(finalAnswer, false, null);
    }

    private Map<String, Object> normalizeArgs(Map<String, Object> args, String username) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) {
                String trimmed = text.trim();
                if (trimmed.equalsIgnoreCase("当前用户")
                        || trimmed.equalsIgnoreCase("current_user")
                        || trimmed.equalsIgnoreCase("current user")
                        || trimmed.equalsIgnoreCase("<current_user>")) {
                    normalized.put(entry.getKey(), username);
                    continue;
                }
            }
            normalized.put(entry.getKey(), value);
        }
        return normalized;
    }

    private boolean shouldShortCircuit(ToolObservation observation) {
        return observation == null || observation.terminal();
    }

    private void recordToolObservation(ActionPlanStep step, ToolObservation observation) {
        if (step == null) {
            return;
        }
        ActionStepObservation detail = ActionStepObservation.fromToolObservation(observation);
        step.setObservation(detail.getMessage());
        step.setObservationDetail(detail);
    }

    private void recordRespondObservation(ActionPlanStep step, String code, String message, Map<String, Object> data) {
        if (step == null) {
            return;
        }
        step.setObservation(message);
        step.setObservationDetail(ActionStepObservation.respond(code, message, data));
    }

    private void recordSystemObservation(ActionPlanStep step, String code, String message, Map<String, Object> data,
                                         boolean terminal, boolean retryable) {
        if (step == null) {
            return;
        }
        step.setObservation(message);
        step.setObservationDetail(ActionStepObservation.system(code, message, false, data, terminal, retryable));
    }

    private void skipRemainingSteps(List<ActionPlanStep> steps, int startIndex) {
        for (int i = startIndex; i < steps.size(); i++) {
            ActionPlanStep step = steps.get(i);
            if (step.getStatus() == ActionStepStatus.PENDING) {
                step.setStatus(ActionStepStatus.SKIPPED);
            }
        }
    }

    private String buildFinalAnswer(String originalQuestion, ActionPlanStep step, List<ToolObservation> observations) {
        if (observations.isEmpty()) {
            return step.getInstruction() == null || step.getInstruction().isBlank()
                    ? "抱歉，我暂时无法完成这次操作。"
                    : step.getInstruction();
        }
        if (observations.size() == 1) {
            return observations.get(0).userMessage();
        }
        String systemPrompt = """
                你是 12306 动作执行总结器。
                你必须严格基于已完成步骤的 observation 生成最终回答，禁止编造新的库存、订单号、车次或规则。
                如果 observation 本身已经是完整面向用户的答复，请尽量直接复用。
                输出简洁中文，不要解释内部规划过程。
                """;
        List<String> formattedObservations = observations.stream()
                .map(observation -> "tool=" + observation.toolName()
                        + ", code=" + observation.code()
                        + ", success=" + observation.success()
                        + ", message=" + observation.userMessage()
                        + ", data=" + observation.data())
                .toList();
        String userPrompt = "【用户问题】" + originalQuestion + "\n"
                + "【RESPOND 指令】" + (step.getInstruction() == null ? "" : step.getInstruction()) + "\n"
                + "【执行 observation】\n- " + String.join("\n- ", formattedObservations);
        String answer = zhipuOfficialAgent.generateAgentText(systemPrompt, userPrompt);
        return answer == null || answer.isBlank()
                ? observations.get(observations.size() - 1).userMessage()
                : answer.trim();
    }

    private String buildReplanReason(ActionPlanStep step, ToolObservation observation, String fallbackReason) {
        StringBuilder reason = new StringBuilder();
        reason.append("步骤 ").append(step.getId()).append(" 执行异常：").append(fallbackReason);
        if (observation != null) {
            reason.append("；tool=").append(observation.toolName());
            if (observation.code() != null && !observation.code().isBlank()) {
                reason.append("；code=").append(observation.code());
            }
            if (observation.userMessage() != null && !observation.userMessage().isBlank()) {
                reason.append("；message=").append(observation.userMessage());
            }
            if (observation.retryable()) {
                reason.append("；retryable=true");
            }
        }
        return reason.toString();
    }

    private ActionPlan parsePlan(String rawPlan) {
        try {
            String json = extractJson(rawPlan);
            if (json == null) {
                return buildRespondOnlyPlan("抱歉，我暂时无法稳定生成执行计划，请稍后重试。");
            }
            return objectMapper.readValue(json, ActionPlan.class);
        } catch (Exception e) {
            log.warn("【PlanAndExecute】解析 planner 输出失败: {}", rawPlan, e);
            return buildRespondOnlyPlan("抱歉，我暂时无法稳定生成执行计划，请稍后重试。");
        }
    }

    private ActionPlan normalizePlan(ActionPlan rawPlan) {
        ActionPlan plan = rawPlan == null ? new ActionPlan() : rawPlan;
        if (plan.getGoal() == null || plan.getGoal().isBlank()) {
            plan.setGoal("完成当前 12306 动作请求");
        }
        List<ActionPlanStep> steps = plan.getSteps() == null ? new ArrayList<>() : new ArrayList<>(plan.getSteps());
        if (steps.isEmpty()) {
            return buildRespondOnlyPlan("当前信息不足，请用户补充后再继续。");
        }

        List<ActionPlanStep> normalized = new ArrayList<>();
        for (int i = 0; i < steps.size() && normalized.size() < MAX_PLAN_STEPS; i++) {
            ActionPlanStep step = steps.get(i);
            if (step == null) {
                continue;
            }
            if (step.getId() == null || step.getId().isBlank()) {
                step.setId("s" + (normalized.size() + 1));
            }
            if (step.getType() == null) {
                step.setType(step.getTool() != null && !step.getTool().isBlank()
                        ? ActionStepType.TOOL
                        : ActionStepType.RESPOND);
            }
            if (step.getArgs() == null) {
                step.setArgs(new LinkedHashMap<>());
            }
            step.setStatus(ActionStepStatus.PENDING);
            normalized.add(step);
        }
        if (normalized.isEmpty()) {
            return buildRespondOnlyPlan("当前信息不足，请用户补充后再继续。");
        }

        ActionPlanStep lastStep = normalized.get(normalized.size() - 1);
        if (lastStep.getType() != ActionStepType.RESPOND) {
            ActionPlanStep respond = new ActionPlanStep();
            respond.setId("s" + (normalized.size() + 1));
            respond.setType(ActionStepType.RESPOND);
            respond.setInstruction("基于已完成步骤，直接告诉用户可见结果。");
            respond.setStatus(ActionStepStatus.PENDING);
            normalized.add(respond);
        }
        plan.setSteps(normalized);
        return plan;
    }

    private ActionPlan buildRespondOnlyPlan(String instruction) {
        ActionPlan plan = new ActionPlan();
        plan.setGoal("向用户返回澄清或兜底答复");
        ActionPlanStep step = new ActionPlanStep();
        step.setId("s1");
        step.setType(ActionStepType.RESPOND);
        step.setInstruction(instruction);
        step.setStatus(ActionStepStatus.PENDING);
        plan.setSteps(List.of(step));
        return plan;
    }

    private String extractJson(String rawPlan) {
        if (rawPlan == null || rawPlan.isBlank()) {
            return null;
        }
        String trimmed = rawPlan.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replace("```json", "").replace("```", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }

    private String buildToolCatalogPrompt() {
        StringBuilder builder = new StringBuilder();
        for (ZhipuOfficialAgent.ToolDescriptor descriptor : zhipuOfficialAgent.getToolDescriptors()) {
            builder.append("- ")
                    .append(descriptor.name())
                    .append(": ")
                    .append(descriptor.description())
                    .append(" | params=")
                    .append(descriptor.parameters())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String buildExecutedObservationContext(ActionPlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return "";
        }
        List<PlanReplanContext> contexts = new ArrayList<>();
        for (ActionPlanStep step : plan.getSteps()) {
            if (step == null || step.getObservationDetail() == null) {
                continue;
            }
            PlanReplanContext context = new PlanReplanContext();
            context.setStepId(step.getId());
            context.setStepType(step.getType());
            context.setTool(step.getTool());
            context.setStatus(step.getStatus());
            context.setObservationDetail(step.getObservationDetail());
            contexts.add(context);
        }
        if (contexts.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(contexts);
        } catch (Exception e) {
            log.debug("【PlanAndExecute】重规划 observation 序列化失败", e);
            return "";
        }
    }

    private String toCompactJson(ActionPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            log.debug("【PlanAndExecute】计划序列化失败", e);
            return "{}";
        }
    }

    private record PlanRunOutcome(String finalAnswer, boolean needReplan, String replanReason) {
    }
}
