package com.ahu.ticket.agent;

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
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OllamaAuxiliaryModelClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${ai.ollama.enabled:false}")
    private boolean enabled;

    @Value("${ai.ollama.base-url:http://127.0.0.1:11434}")
    private String baseUrl;

    @Value("${ai.ollama.light-model:qwen2.5:7b}")
    private String lightModel;

    @Value("${ai.ollama.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${ai.ollama.read-timeout-ms:20000}")
    private int readTimeoutMs;

    public boolean enabled() {
        return enabled;
    }

    public String chat(String systemPrompt, String userPrompt) {
        if (!enabled) {
            return null;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs)))
                    .build();

            Map<String, Object> requestBody = Map.of(
                    "model", lightModel,
                    "stream", false,
                    "options", Map.of("temperature", 0),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                            Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)
                    )
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(baseUrl) + "/api/chat"))
                    .timeout(Duration.ofMillis(Math.max(1000, readTimeoutMs)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("【Ollama】调用失败 status={}, body={}", response.statusCode(), truncate(response.body()));
                return null;
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode contentNode = root.path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
                log.warn("【Ollama】响应缺少正文 body={}", truncate(response.body()));
                return null;
            }
            return contentNode.asText().trim();
        } catch (Exception e) {
            log.warn("【Ollama】轻模型调用失败 model={}, err={}", lightModel, e.getMessage());
            return null;
        }
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:11434";
        }
        String normalized = url.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }
}
