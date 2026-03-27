package com.ahu.ticket.rag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 内存级 BM25 关键词检索器
 *
 * 面试亮点：手写 BM25 算法，无需额外中间件（如 Elasticsearch）。
 * 作为混合检索的 Sparse 通道，与 Milvus 向量检索（Dense 通道）配合使用。
 *
 * BM25 公式:
 * score(q, d) = Σ IDF(qi) * (tf(qi, d) * (k1 + 1)) / (tf(qi, d) + k1 * (1 - b + b * |d| / avgdl))
 *
 * 参数: k1=1.5 (词频饱和度), b=0.75 (文档长度归一化)
 */
public class BM25Retriever {

    // ============ BM25 超参数 ============
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    // ============ 中文停用词表 ============
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "他", "她",
            "吗", "把", "那", "它", "呢", "被", "从", "为", "以", "及",
            "对", "与", "之", "但", "而", "或", "其", "可以", "这个", "那个"
    );

    // ============ 文档元数据 ============
    /** 存储每个文档的信息 */
    public static class DocumentInfo {
        private final String docId;
        private final String text;
        private final String parentId;
        private final String parentText;
        private final List<String> tokens;

        public DocumentInfo(String docId, String text, String parentId, String parentText, List<String> tokens) {
            this.docId = docId;
            this.text = text;
            this.parentId = parentId;
            this.parentText = parentText;
            this.tokens = tokens;
        }

        public String getDocId() { return docId; }
        public String getText() { return text; }
        public String getParentId() { return parentId; }
        public String getParentText() { return parentText; }
        public List<String> getTokens() { return tokens; }
    }

    /** BM25 检索结果 */
    public static class ScoredDocument {
        private final DocumentInfo document;
        private final double score;

        public ScoredDocument(DocumentInfo document, double score) {
            this.document = document;
            this.score = score;
        }

        public DocumentInfo getDocument() { return document; }
        public double getScore() { return score; }
    }

    // ============ 核心数据结构 ============
    /** docId → DocumentInfo */
    private final Map<String, DocumentInfo> documents = new ConcurrentHashMap<>();
    /** term → 包含该 term 的 docId 集合 (倒排索引) */
    private final Map<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();
    /** docId → { term → 词频 } */
    private final Map<String, Map<String, Integer>> termFrequencies = new ConcurrentHashMap<>();
    /** 所有文档长度总和 (用于计算 avgdl) */
    private long totalDocLength = 0;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ============ 中文分词器 (基于字符级别) ============

    /**
     * 简易中文分词：按标点和空白切割后，再按 unigram + bigram 进行切分。
     * 面试说明：生产环境可替换为 jieba/HanLP 等分词器，此处为零依赖实现。
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        // 1. 统一小写，去除标点符号，按空白/标点分割成词块
        String normalized = text.toLowerCase()
                .replaceAll("[\\p{Punct}\\p{P}]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        List<String> tokens = new ArrayList<>();
        String[] segments = normalized.split("\\s+");

        for (String segment : segments) {
            if (segment.isEmpty()) continue;

            // 2. 判断是否为纯英文/数字 → 整词保留
            if (segment.matches("[a-z0-9]+")) {
                tokens.add(segment);
                continue;
            }

            // 3. 中文：unigram（单字）+ bigram（双字组合）
            char[] chars = segment.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                String unigram = String.valueOf(chars[i]);
                if (!STOP_WORDS.contains(unigram)) {
                    tokens.add(unigram);
                }
                // bigram
                if (i + 1 < chars.length) {
                    String bigram = new String(chars, i, 2);
                    if (!STOP_WORDS.contains(bigram)) {
                        tokens.add(bigram);
                    }
                }
            }
        }

        // 4. 过滤停用词
        return tokens.stream()
                .filter(t -> !STOP_WORDS.contains(t) && t.length() > 0)
                .collect(Collectors.toList());
    }

    // ============ 索引操作 ============

    /**
     * 将一个 Child 段落加入 BM25 索引。
     *
     * @param docId      唯一标识 (推荐: parentId:childIndex)
     * @param text       Child 的原始文本
     * @param parentId   所属 Parent 的 ID
     * @param parentText 所属 Parent 的完整文本
     */
    public void addDocument(String docId, String text, String parentId, String parentText) {
        lock.writeLock().lock();
        try {
            List<String> tokens = tokenize(text);
            DocumentInfo doc = new DocumentInfo(docId, text, parentId, parentText, tokens);
            documents.put(docId, doc);

            // 构建词频表
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            termFrequencies.put(docId, tf);

            // 更新倒排索引
            for (String term : tf.keySet()) {
                invertedIndex.computeIfAbsent(term, k -> ConcurrentHashMap.newKeySet()).add(docId);
            }

            // 更新文档总长度
            totalDocLength += tokens.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * BM25 检索：对 query 分词后，计算每个文档的 BM25 分数，返回 Top-K。
     *
     * @param query 用户问题
     * @param topK  返回前 K 个结果
     * @return 按分数降序排列的 ScoredDocument 列表
     */
    public List<ScoredDocument> search(String query, int topK) {
        lock.readLock().lock();
        try {
            if (documents.isEmpty()) {
                return Collections.emptyList();
            }

            List<String> queryTokens = tokenize(query);
            if (queryTokens.isEmpty()) {
                return Collections.emptyList();
            }

            int totalDocs = documents.size();
            double avgdl = (double) totalDocLength / totalDocs;

            // 收集所有候选文档（至少包含一个 query token 的文档）
            Set<String> candidateDocIds = new HashSet<>();
            for (String token : queryTokens) {
                Set<String> docIds = invertedIndex.get(token);
                if (docIds != null) {
                    candidateDocIds.addAll(docIds);
                }
            }

            // 计算每个候选文档的 BM25 分数
            List<ScoredDocument> results = new ArrayList<>();
            for (String docId : candidateDocIds) {
                DocumentInfo doc = documents.get(docId);
                Map<String, Integer> tf = termFrequencies.get(docId);
                if (doc == null || tf == null) continue;

                double score = 0.0;
                int docLen = doc.getTokens().size();

                for (String token : queryTokens) {
                    // IDF: log((N - n + 0.5) / (n + 0.5) + 1)
                    Set<String> docsWithTerm = invertedIndex.get(token);
                    if (docsWithTerm == null) continue;
                    int n = docsWithTerm.size();
                    double idf = Math.log((totalDocs - n + 0.5) / (n + 0.5) + 1.0);

                    // TF 归一化
                    int termFreq = tf.getOrDefault(token, 0);
                    double tfNorm = (termFreq * (K1 + 1))
                            / (termFreq + K1 * (1 - B + B * docLen / avgdl));

                    score += idf * tfNorm;
                }

                results.add(new ScoredDocument(doc, score));
            }

            // 按分数降序排序，取 Top-K
            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return results.stream().limit(topK).collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取当前索引中的文档数量。
     */
    public int size() {
        return documents.size();
    }

    /**
     * 清空索引。
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            documents.clear();
            invertedIndex.clear();
            termFrequencies.clear();
            totalDocLength = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
