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
import java.time.Duration;
import java.util.*;

/**
 * 重排模型 (Reranker) —— 面试加分大杀器
 *
 * 功能定位：在混合检索(Dense + Sparse + RRF)粗排召回之后，
 * 利用专用 Rerank API 对 (query, documents[]) 进行一次批量精排。
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
    private static final URI RERANK_API_URI = URI.create("https://open.bigmodel.cn/api/paas/v4/rerank");
    private static final int MAX_TEXT_LENGTH = 4096;

    @Value("${ai.zhipu.api-key}")
    private String apiKey;

    @Value("${ai.zhipu.rerank-model:rerank}")
    private String rerankModelName;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
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

        if (topK <= 0) {
            return Collections.emptyList();
        }

        // 如果候选数量 <= topK，无需精排直接返回
        if (candidates.size() <= topK) {
            return new ArrayList<>(candidates);
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("【Reranker 批量精排】未配置 apiKey，降级返回粗排 Top-{}", topK);
            return fallbackTopK(candidates, topK);
        }

        try {
            return rerankByBatchApi(query, candidates, topK);
        } catch (Exception e) {
            log.error("【Reranker 批量精排】异常，降级返回粗排 Top-{}: {}", topK, e.getMessage());
            // 降级策略：Reranker 挂了就原样返回粗排 Top-K，保证系统不宕机
            return fallbackTopK(candidates, topK);
        }
    }

    /**
     * 使用官方批量 rerank API 一次性对多个候选做精排，避免逐条 chat completion。
     */
    private List<String> rerankByBatchApi(String query, List<String> candidates, int topK) throws Exception {
        List<String> truncatedDocuments = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            truncatedDocuments.add(truncate(candidate, MAX_TEXT_LENGTH));
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", normalizeRerankModel());
        requestBody.put("query", truncate(query, MAX_TEXT_LENGTH));
        requestBody.put("documents", truncatedDocuments);
        requestBody.put("top_n", Math.min(topK, candidates.size()));
        requestBody.put("return_documents", true);
        requestBody.put("request_id", UUID.randomUUID().toString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(RERANK_API_URI)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        long startNs = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long costMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " body=" + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("rerank results is empty");
        }

        List<String> reranked = new ArrayList<>(topK);
        Set<Integer> selectedIndexes = new LinkedHashSet<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            if (index < 0 || index >= candidates.size() || !selectedIndexes.add(index)) {
                continue;
            }
            reranked.add(candidates.get(index));
            if (reranked.size() >= topK) {
                break;
            }
        }

        if (reranked.isEmpty()) {
            throw new IllegalStateException("rerank results contained no valid indexes");
        }

        for (int i = 0; i < candidates.size() && reranked.size() < topK; i++) {
            if (selectedIndexes.add(i)) {
                reranked.add(candidates.get(i));
            }
        }

        log.info("【Reranker 批量精排】模型={} 候选={} 输出={} 耗时={}ms",
                normalizeRerankModel(), candidates.size(), reranked.size(), costMs);
        return reranked;
    }

    /** 截断超长文本，防止 Token 溢出 */
    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    private String normalizeRerankModel() {
        if (rerankModelName == null || rerankModelName.isBlank()) {
            return "rerank";
        }
        return rerankModelName;
    }

    private List<String> fallbackTopK(List<String> candidates, int topK) {
        return new ArrayList<>(candidates.subList(0, Math.min(topK, candidates.size())));
    }
}
