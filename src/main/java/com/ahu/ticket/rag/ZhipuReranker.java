package com.ahu.ticket.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排模型 (Reranker) —— 面试加分大杀器
 *
 * 功能定位：在混合检索(Dense + Sparse + RRF)粗排召回之后，
 * 利用 Cross-Encoder 架构对每一个 (query, document) 对进行点对点精排打分。
 *
 * 架构漏斗：
 * ┌──────────────────────────────────────────────┐
 * │ 混合检索粗排 (RRF) → 召回 Top-10 Parent 块 │
 * │ ↓ │
 * │ Reranker 精排 (本类) → 输出 Top-3 最精准块 │
 * │ ↓ │
 * │ 大模型生成 (LLM) → 最终回答 │
 * └──────────────────────────────────────────────┘
 *
 * 面试话术要点：
 * - 混合检索负责"粗排召回"（扩大召回率，防止遗漏关键信息）
 * - Reranker 负责"精排打分"（提高精确率，优中选优）
 * - 这一粗一精的漏斗架构，确保喂给大模型的上下文绝对纯净
 */
@Slf4j
@Component
public class ZhipuReranker {
    @Value("${ai.zhipu.api-key}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 对粗排结果进行精排重新打分
     *
     * @param query      用户提问
     * @param candidates 粗排召回的候选文本列表
     * @param topK       最终返回的精排结果数量
     * @return 按相关度降序排列的精排后文本列表
     */
    public List<String> rerank(String query, List<String> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 如果候选数量 <= topK，无需精排直接返回
        if (candidates.size() <= topK) {
            return new ArrayList<>(candidates);
        }

        try {
            // 使用大模型进行逐对打分（Cross-Encoder 逻辑）
            List<ScoredCandidate> scoredList = new ArrayList<>();

            for (int i = 0; i < candidates.size(); i++) {
                double score = computeRelevanceScore(query, candidates.get(i));
                scoredList.add(new ScoredCandidate(i, candidates.get(i), score));
            }

            // 按得分降序排列取 Top-K
            return scoredList.stream()
                    .sorted((a, b) -> Double.compare(b.score, a.score))
                    .limit(topK)
                    .map(sc -> sc.text)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("【Reranker】精排异常，降级返回粗排结果: " + e.getMessage());
            // 降级策略：Reranker 挂了就原样返回粗排 Top-K，保证系统不宕机
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }
    }

    /**
     * 利用智谱 GLM 模型计算 query 与单个 candidate 的相关度得分
     * 原理：构造一个极简的 Prompt，让大模型输出一个 0~1 的相关度分数
     * 这本质上模拟了 Cross-Encoder 的点对点打分行为
     */
    private double computeRelevanceScore(String query, String candidate) {
        try {
            String prompt = "请判断以下【参考文段】与【用户提问】的相关度。" +
                    "只允许输出一个0到1之间的浮点数，1表示完全相关，0表示毫无关系。" +
                    "严禁输出任何解释文字。\n\n" +
                    "【用户提问】：" + query + "\n" +
                    "【参考文段】：" + truncate(candidate, 500) + "\n\n" +
                    "相关度评分：";

            // 构建智谱 API 请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "glm-4-flash"); // 使用免费的 flash 版本，极速且省钱
            requestBody.put("temperature", 0.1); // 极低温，确保评分一致性

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));
            requestBody.put("messages", messages);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());

            String scoreText = root.path("choices").get(0)
                    .path("message").path("content").asText().trim();

            // 提取浮点数
            String numericPart = scoreText.replaceAll("[^0-9.]", "");
            double score = Double.parseDouble(numericPart);
            return Math.min(Math.max(score, 0.0), 1.0);

        } catch (Exception e) {
            log.error("【Reranker 打分异常】: " + e.getMessage());
            return 0.5; // 异常时给中间分，不影响排序稳定性
        }
    }

    /** 截断超长文本，防止 Token 溢出 */
    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    /** 带分数的候选项 */
    private static class ScoredCandidate {
        final int index;
        final String text;
        final double score;

        ScoredCandidate(int index, String text, double score) {
            this.index = index;
            this.text = text;
            this.score = score;
        }
    }
}
