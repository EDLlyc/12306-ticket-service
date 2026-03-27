package com.ahu.ticket.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Parent-Child 双层检索器
 *
 * 面试亮点：小块(Child)用于精准向量检索，命中后返回大块(Parent)上下文给 LLM，
 * 兼顾了检索精度和上下文完整性，是业界 RAG 优化的最佳实践之一。
 *
 * 原理：
 * 1. 用户问题 → Embedding → 在 Milvus 中搜索最相似的 Child 小块
 * 2. 命中的 Child 小块 metadata 里存着 parent_id 和 parent_text
 * 3. 按 parent_id 去重，避免同一个 Parent 被重复返回
 * 4. 最终把 Parent 大块原文作为上下文返回给大模型
 */
public class ParentChildContentRetriever implements ContentRetriever {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final int maxResults;
    private final double minScore;

    public ParentChildContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                       EmbeddingModel embeddingModel,
                                       int maxResults,
                                       double minScore) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.maxResults = maxResults;
        this.minScore = minScore;
    }

    @Override
    public List<Content> retrieve(Query query) {
        // 1. 将用户问题向量化
        Embedding queryEmbedding = embeddingModel.embed(query.text()).content();

        // 2. 搜索 Child 小块（多搜一些，去重后才够数）
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults * 3)  // 搜3倍数量的child，确保去重后parent够用
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        // 3. 按 parent_id 去重，提取 parent_text
        // 使用 LinkedHashMap 保持相似度排序
        Map<String, String> parentTexts = new LinkedHashMap<>();

        for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
            TextSegment segment = match.embedded();
            if (segment == null || segment.metadata() == null) {
                continue;
            }

            String parentId = segment.metadata().getString("parent_id");
            String parentText = segment.metadata().getString("parent_text");

            // 有 parent 信息 → Parent-Child 模式，返回 Parent 大块
            if (parentId != null && parentText != null && !parentTexts.containsKey(parentId)) {
                parentTexts.put(parentId, parentText);
            }
            // 没有 parent 信息 → 兼容旧数据，直接返回原文
            else if (parentId == null) {
                String fallbackKey = "legacy_" + match.embeddingId();
                if (!parentTexts.containsKey(fallbackKey)) {
                    parentTexts.put(fallbackKey, segment.text());
                }
            }

            // 收集够了就停
            if (parentTexts.size() >= maxResults) {
                break;
            }
        }

        // 4. 转成 Content 列表返回给大模型
        return parentTexts.values().stream()
                .map(text -> Content.from(TextSegment.from(text)))
                .collect(Collectors.toList());
    }

    // ============ Builder 模式 ============
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private EmbeddingStore<TextSegment> embeddingStore;
        private EmbeddingModel embeddingModel;
        private int maxResults = 3;
        private double minScore = 0.6;

        public Builder embeddingStore(EmbeddingStore<TextSegment> embeddingStore) {
            this.embeddingStore = embeddingStore;
            return this;
        }

        public Builder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public Builder minScore(double minScore) {
            this.minScore = minScore;
            return this;
        }

        public ParentChildContentRetriever build() {
            return new ParentChildContentRetriever(embeddingStore, embeddingModel, maxResults, minScore);
        }
    }
}
