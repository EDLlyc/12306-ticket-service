package com.ahu.ticket.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * OpenSearch-backed sparse retriever.
 *
 * 对外保持 BM25Retriever 接口，内部使用 OpenSearch 原生 BM25 执行检索。
 * 当 OpenSearch 不可用时，自动降级到本地内存 BM25Retriever。
 */
public class OpenSearchBM25Retriever extends BM25Retriever {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchBM25Retriever.class);

    private final String endpoint;
    private final String indexName;
    private final String authHeader;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final BM25Retriever localFallback = new BM25Retriever();
    private volatile boolean remoteHealthy = false;

    public OpenSearchBM25Retriever(String endpoint, String indexName, String username, String password) {
        this.endpoint = trimTrailingSlash(endpoint);
        this.indexName = indexName;
        this.authHeader = buildAuthHeader(username, password);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        initIndex();
    }

    @Override
    public void addDocument(String docId, String text, String parentId, String parentText) {
        // 始终写本地兜底，确保远端异常时仍可检索
        localFallback.addDocument(docId, text, parentId, parentText);

        if (!remoteHealthy) {
            return;
        }

        try {
            String payload = mapper.writeValueAsString(new IndexDoc(docId, text, parentId, parentText));
            String encodedId = URLEncoder.encode(docId, StandardCharsets.UTF_8);
            HttpRequest request = baseRequest("/" + indexName + "/_doc/" + encodedId + "?refresh=wait_for")
                    .PUT(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                remoteHealthy = false;
                log.warn("OpenSearch 写入失败，转入本地 BM25 降级。status={}, body={}",
                        response.statusCode(), safeSnippet(response.body()));
            }
        } catch (Exception e) {
            remoteHealthy = false;
            log.warn("OpenSearch 写入异常，转入本地 BM25 降级。error={}", e.getMessage());
        }
    }

    @Override
    public List<ScoredDocument> search(String query, int topK) {
        if (!remoteHealthy) {
            return localFallback.search(query, topK);
        }

        try {
            String payload = """
                    {
                      "size": %d,
                      "_source": ["docId", "text", "parentId", "parentText"],
                      "query": {
                        "bool": {
                          "should": [
                            {
                              "multi_match": {
                                "query": %s,
                                "fields": ["text^3", "parentText"],
                                "type": "best_fields"
                              }
                            },
                            {
                              "match_phrase": {
                                "text": {
                                  "query": %s,
                                  "boost": 2.5
                                }
                              }
                            }
                          ],
                          "minimum_should_match": 1
                        }
                      }
                    }
                    """.formatted(topK, mapper.writeValueAsString(query), mapper.writeValueAsString(query));

            HttpRequest request = baseRequest("/" + indexName + "/_search")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                remoteHealthy = false;
                log.warn("OpenSearch 检索失败，回退本地 BM25。status={}, body={}",
                        response.statusCode(), safeSnippet(response.body()));
                return localFallback.search(query, topK);
            }
            return parseSearchResults(response.body());
        } catch (Exception e) {
            remoteHealthy = false;
            log.warn("OpenSearch 检索异常，回退本地 BM25。error={}", e.getMessage());
            return localFallback.search(query, topK);
        }
    }

    @Override
    public int size() {
        if (!remoteHealthy) {
            return localFallback.size();
        }
        try {
            HttpRequest request = baseRequest("/" + indexName + "/_count")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":{\"match_all\":{}}}"))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return localFallback.size();
            }
            JsonNode root = mapper.readTree(response.body());
            return root.path("count").asInt(localFallback.size());
        } catch (Exception e) {
            return localFallback.size();
        }
    }

    @Override
    public void clear() {
        localFallback.clear();
        if (!remoteHealthy) {
            return;
        }
        try {
            HttpRequest request = baseRequest("/" + indexName + "/_delete_by_query?refresh=true")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":{\"match_all\":{}}}"))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignore) {
            // clear 失败不影响主流程
        }
    }

    private void initIndex() {
        try {
            HttpRequest headReq = baseRequest("/" + indexName).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> headResp = httpClient.send(headReq, HttpResponse.BodyHandlers.ofString());
            if (headResp.statusCode() == 200) {
                remoteHealthy = true;
                return;
            }

            String createBody = """
                    {
                      "settings": {
                        "number_of_shards": 1,
                        "number_of_replicas": 0,
                        "refresh_interval": "1s"
                      },
                      "mappings": {
                        "properties": {
                          "docId": { "type": "keyword" },
                          "text": { "type": "text" },
                          "parentId": { "type": "keyword" },
                          "parentText": { "type": "text" }
                        }
                      }
                    }
                    """;
            HttpRequest createReq = baseRequest("/" + indexName)
                    .PUT(HttpRequest.BodyPublishers.ofString(createBody))
                    .build();
            HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
            remoteHealthy = createResp.statusCode() / 100 == 2 || createResp.statusCode() == 400;
            if (remoteHealthy) {
                log.info("OpenSearch sparse index ready: {}", indexName);
            } else {
                log.warn("OpenSearch index init failed, fallback to local BM25. status={}, body={}",
                        createResp.statusCode(), safeSnippet(createResp.body()));
            }
        } catch (Exception e) {
            remoteHealthy = false;
            log.warn("OpenSearch unavailable, fallback to local BM25. error={}", e.getMessage());
        }
    }

    private List<ScoredDocument> parseSearchResults(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode hits = root.path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredDocument> results = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            String docId = textValue(source, "docId");
            String text = textValue(source, "text");
            String parentId = textValue(source, "parentId");
            String parentText = textValue(source, "parentText");
            double score = hit.path("_score").asDouble(0.0d);

            List<String> tokens = text == null ? Collections.emptyList() : List.of();
            DocumentInfo doc = new DocumentInfo(docId, text, text, parentId, parentText, tokens);
            results.add(new ScoredDocument(doc, score));
        }
        return results;
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private HttpRequest.Builder baseRequest(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json");
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return builder;
    }

    private String buildAuthHeader(String username, String password) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String raw = username + ":" + (password == null ? "" : password);
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:9200";
        }
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String safeSnippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    private record IndexDoc(String docId, String text, String parentId, String parentText) {
    }
}
