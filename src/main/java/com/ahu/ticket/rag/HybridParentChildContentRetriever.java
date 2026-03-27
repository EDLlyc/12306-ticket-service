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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 混合检索器 (Hybrid Search) = Dense + Sparse + RRF 融合 + Parent-Child
 *
 * 面试亮点：同时使用向量语义检索和 BM25 关键词检索，通过 RRF 融合算法
 * 合并两路结果，平衡"语义匹配"和"精确关键词匹配"的优势。
 *
 * 检索流程：
 * ┌──────────────────────────────────────────────────────────┐
 * │  用户问题                                                 │
 * │    ├── Dense 通道: Embedding → Milvus → Child 列表        │
 * │    ├── Sparse 通道: BM25 → 内存索引 → Child 列表          │
 * │    └── RRF 粗排融合: 按 parentId 聚合 → 去重 → Top-N      │
 * │         └── Reranker 精排: Cross-Encoder 逐对打分 → Top-K  │
 * │              └── 返回最精准的 Parent 大块上下文给 LLM       │
 * └──────────────────────────────────────────────────────────┘
 *
 * RRF 公式: score(d) = Σ 1 / (k + rank_i(d))
 * 其中 k=60 是平滑常数，rank_i 是文档在第 i 路检索中的排名
 */
public class HybridParentChildContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridParentChildContentRetriever.class);

    // ============ RRF 融合参数 ============
    /** RRF 平滑常数 k，业界标准值为 60 */
    private static final int RRF_K = 60;

    // ============ 依赖组件 ============
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final BM25Retriever bm25Retriever;
    private final ZhipuReranker reranker; // 精排模型
    private final int maxResults;
    private final double minScore;

    public HybridParentChildContentRetriever(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            BM25Retriever bm25Retriever,
            ZhipuReranker reranker,
            int maxResults,
            double minScore) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.bm25Retriever = bm25Retriever;
        this.reranker = reranker;
        this.maxResults = maxResults;
        this.minScore = minScore;
    }

    @Override
    public List<Content> retrieve(Query query) {
        // ====== 第一路：Dense 通道 (Milvus 向量检索) ======
        List<RankedParent> denseResults = retrieveByDense(query.text());

        // ====== 第二路：Sparse 通道 (BM25 关键词检索) ======
        List<RankedParent> sparseResults = retrieveBySparse(query.text());

        // ====== RRF 融合 ======
        Map<String, FusionEntry> fusionMap = new LinkedHashMap<>();

        // 合并 Dense 通道结果
        for (int rank = 0; rank < denseResults.size(); rank++) {
            RankedParent rp = denseResults.get(rank);
            fusionMap.computeIfAbsent(rp.parentId, k -> new FusionEntry(rp.parentId, rp.parentText))
                    .addRRFScore(rank + 1); // rank 从 1 开始
        }

        // 合并 Sparse 通道结果
        for (int rank = 0; rank < sparseResults.size(); rank++) {
            RankedParent rp = sparseResults.get(rank);
            fusionMap.computeIfAbsent(rp.parentId, k -> new FusionEntry(rp.parentId, rp.parentText))
                    .addRRFScore(rank + 1);
        }

        // 按 RRF 融合分数降序排序，取扩大版 Top-N（多取一些留给 Reranker 精排）
        List<FusionEntry> coarseResults = fusionMap.values().stream()
                .sorted((a, b) -> Double.compare(b.rrfScore, a.rrfScore))
                .limit(maxResults * 3) // 粗排多召回一些，交给 Reranker 精排过滤
                .collect(Collectors.toList());

        // ====== Reranker 精排 (面试亮点: Cross-Encoder 漏斗) ======
        if (reranker != null && coarseResults.size() > maxResults) {
            log.info("【Reranker 精排】粗排召回 " + coarseResults.size() + " 条，正在进行 Cross-Encoder 精排...");
            List<String> candidateTexts = coarseResults.stream()
                    .map(e -> e.parentText)
                    .collect(Collectors.toList());

            List<String> rerankedTexts = reranker.rerank(query.text(), candidateTexts, maxResults);

            log.info("【Reranker 精排】精排完成，最终输出 Top-" + rerankedTexts.size() + " 最精准上下文！");
            return rerankedTexts.stream()
                    .map(text -> Content.from(TextSegment.from(text)))
                    .collect(Collectors.toList());
        }

        // 如果 Reranker 不可用，降级为粗排直出
        return coarseResults.stream()
                .limit(maxResults)
                .map(entry -> Content.from(TextSegment.from(entry.parentText)))
                .collect(Collectors.toList());
    }

    // ====================================================================
    // Dense 通道：Milvus 向量检索
    // ====================================================================
    private List<RankedParent> retrieveByDense(String queryText) {
        Embedding queryEmbedding = embeddingModel.embed(queryText).content();

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults * 3)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

        // 按 parentId 去重，保留首次出现的排名
        Map<String, RankedParent> parentMap = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : searchResult.matches()) {
            TextSegment segment = match.embedded();
            if (segment == null || segment.metadata() == null) continue;

            String parentId = segment.metadata().getString("parent_id");
            String parentText = segment.metadata().getString("parent_text");

            if (parentId != null && parentText != null && !parentMap.containsKey(parentId)) {
                parentMap.put(parentId, new RankedParent(parentId, parentText));
            }
            // 兼容旧数据（无 parent 信息）
            else if (parentId == null) {
                String fallbackKey = "legacy_" + match.embeddingId();
                if (!parentMap.containsKey(fallbackKey)) {
                    parentMap.put(fallbackKey, new RankedParent(fallbackKey, segment.text()));
                }
            }

            if (parentMap.size() >= maxResults * 2) break;
        }

        return new ArrayList<>(parentMap.values());
    }

    // ====================================================================
    // Sparse 通道：BM25 关键词检索
    // ====================================================================
    private List<RankedParent> retrieveBySparse(String queryText) {
        List<BM25Retriever.ScoredDocument> bm25Results = bm25Retriever.search(queryText, maxResults * 3);

        // 按 parentId 去重
        Map<String, RankedParent> parentMap = new LinkedHashMap<>();
        for (BM25Retriever.ScoredDocument scored : bm25Results) {
            BM25Retriever.DocumentInfo doc = scored.getDocument();
            String parentId = doc.getParentId();
            String parentText = doc.getParentText();

            if (parentId != null && parentText != null && !parentMap.containsKey(parentId)) {
                parentMap.put(parentId, new RankedParent(parentId, parentText));
            }

            if (parentMap.size() >= maxResults * 2) break;
        }

        return new ArrayList<>(parentMap.values());
    }

    // ====================================================================
    // 内部辅助类
    // ====================================================================

    /** 去重后的 Parent 条目 (带 parentId) */
    private static class RankedParent {
        final String parentId;
        final String parentText;

        RankedParent(String parentId, String parentText) {
            this.parentId = parentId;
            this.parentText = parentText;
        }
    }

    /** RRF 融合计算条目 */
    private static class FusionEntry {
        final String parentId;
        final String parentText;
        double rrfScore = 0.0;

        FusionEntry(String parentId, String parentText) {
            this.parentId = parentId;
            this.parentText = parentText;
        }

        /** 累加一次 RRF 贡献分: 1 / (k + rank) */
        void addRRFScore(int rank) {
            this.rrfScore += 1.0 / (RRF_K + rank);
        }
    }

    // ============ Builder 模式 ============
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private EmbeddingStore<TextSegment> embeddingStore;
        private EmbeddingModel embeddingModel;
        private BM25Retriever bm25Retriever;
        private ZhipuReranker reranker;
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

        public Builder bm25Retriever(BM25Retriever bm25Retriever) {
            this.bm25Retriever = bm25Retriever;
            return this;
        }

        public Builder reranker(ZhipuReranker reranker) {
            this.reranker = reranker;
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

        public HybridParentChildContentRetriever build() {
            Objects.requireNonNull(embeddingStore, "embeddingStore must not be null");
            Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
            Objects.requireNonNull(bm25Retriever, "bm25Retriever must not be null");
            return new HybridParentChildContentRetriever(
                    embeddingStore, embeddingModel, bm25Retriever, reranker, maxResults, minScore);
        }
    }
}
