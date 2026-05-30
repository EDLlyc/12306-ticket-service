package com.ahu.ticket.agent.plan;

import com.ahu.ticket.agent.ZhipuOfficialAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;

@Slf4j
@Component
public class PolicyPlanService {

    private static final int MAX_SUB_QUESTIONS = 3;

    private final ZhipuOfficialAgent zhipuOfficialAgent;
    private final ObjectMapper objectMapper;

    public PolicyPlanService(ZhipuOfficialAgent zhipuOfficialAgent) {
        this.zhipuOfficialAgent = zhipuOfficialAgent;
        this.objectMapper = new ObjectMapper();
    }

    public PolicyQuestionPlan plan(String question) {
        if (question == null || question.isBlank()) {
            return PolicyQuestionPlan.fallback(question);
        }
        try {
            String systemPrompt = """
                    你是 12306 规章检索规划器。
                    你的目标是把一个复杂政策问题拆成 2 到 3 个适合独立检索的子问题。
                    你必须只输出 JSON，禁止输出 Markdown、解释或前后缀。
                    JSON schema 固定为：
                    {"goal":string,"subQuestions":[string,...],"synthesisInstruction":string}
                    规则：
                    1. subQuestions 最多 3 个。
                    2. 子问题必须紧贴用户原问题，不要扩展到用户没问的维度。
                    3. 如果原问题本身已经足够单一，就只输出 1 个子问题。
                    4. 子问题要可直接拿去做知识库检索，尽量具体、短句。
                    5. synthesisInstruction 用一句中文说明最终回答应该如何整合证据。
                    """;
            String userPrompt = "【用户问题】" + question;
            String raw = zhipuOfficialAgent.generatePolicyText(systemPrompt, userPrompt, false);
            PolicyQuestionPlan parsed = parse(raw, question);
            return normalize(parsed, question);
        } catch (Exception e) {
            log.warn("【PolicyPlan】生成失败，回退单问题检索。question={}", question, e);
            return PolicyQuestionPlan.fallback(question);
        }
    }

    private PolicyQuestionPlan parse(String raw, String question) {
        try {
            String json = extractJson(raw);
            if (json == null) {
                return PolicyQuestionPlan.fallback(question);
            }
            return objectMapper.readValue(json, PolicyQuestionPlan.class);
        } catch (Exception e) {
            log.warn("【PolicyPlan】解析失败，raw={}", raw, e);
            return PolicyQuestionPlan.fallback(question);
        }
    }

    private PolicyQuestionPlan normalize(PolicyQuestionPlan plan, String question) {
        if (plan == null) {
            return PolicyQuestionPlan.fallback(question);
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String subQuestion : plan.subQuestions()) {
            if (subQuestion == null) {
                continue;
            }
            String normalized = subQuestion.trim().replaceAll("\\s+", " ");
            if (!normalized.isEmpty()) {
                deduped.add(normalized);
            }
            if (deduped.size() >= MAX_SUB_QUESTIONS) {
                break;
            }
        }
        if (deduped.isEmpty() && question != null && !question.isBlank()) {
            deduped.add(question.trim());
        }
        String goal = plan.goal() == null || plan.goal().isBlank() ? "回答当前政策问题" : plan.goal().trim();
        String instruction = plan.synthesisInstruction() == null || plan.synthesisInstruction().isBlank()
                ? "综合所有子问题的检索证据，直接回答用户原始问题。"
                : plan.synthesisInstruction().trim();
        return new PolicyQuestionPlan(goal, new ArrayList<>(deduped), instruction);
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
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
}
