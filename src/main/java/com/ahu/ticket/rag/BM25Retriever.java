package com.ahu.ticket.rag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内存级 BM25 关键词检索器（增强版）
 *
 * 面试亮点：零中间件实现，吸收业界常用实践：
 * 1) 中英数混合文本多粒度分词（英文词、数字串、中文2/3-gram）
 * 2) 动态高频词抑制（近似 ES stopword 过滤效果）
 * 3) BM25+ 打分（缓解长文档/低词频场景的过度惩罚）
 * 4) 短语命中加分（提升规则条款、专有词表达命中）
 * 作为混合检索的 Sparse 通道，与 Milvus 向量检索（Dense 通道）配合使用。
 *
 * BM25+ 公式:
 * score(q, d) = Σ IDF(qi) * ( ((tf(qi, d) * (k1 + 1)) / (tf(qi, d) + k1 * (1 - b + b * |d| / avgdl))) + δ )
 *
 * 参数: k1=1.5 (词频饱和度), b=0.75 (文档长度归一化), δ=0.3 (BM25+平移项)
 */
public class BM25Retriever {

    // ============ BM25+ 超参数 ============
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final double DELTA = 0.3;
    private static final double HIGH_DF_RATIO = 0.85; // 动态高频词阈值
    private static final double PHRASE_BONUS = 0.12;  // 短语命中加分
    private static final int MAX_PHRASE_BONUS_TERMS = 8;

    // ============ 中文停用词表 ============
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "他", "她",
            "吗", "把", "那", "它", "呢", "被", "从", "为", "以", "及",
            "对", "与", "之", "但", "而", "或", "其", "可以", "这个", "那个"
    );

    // 中英数 token 抽取：英文词、数字串、中文连续串
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z]+\\d*|\\d+|[\\u4e00-\\u9fa5]+");

    // ============ 文档元数据 ============
    /** 存储每个文档的信息 */
    public static class DocumentInfo {
        private final String docId;
        private final String text;
        private final String normalizedText;
        private final String parentId;
        private final String parentText;
        private final List<String> tokens;

        public DocumentInfo(String docId, String text, String normalizedText, String parentId, String parentText, List<String> tokens) {
            this.docId = docId;
            this.text = text;
            this.normalizedText = normalizedText;
            this.parentId = parentId;
            this.parentText = parentText;
            this.tokens = tokens;
        }

        public String getDocId() { return docId; }
        public String getText() { return text; }
        public String getNormalizedText() { return normalizedText; }
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
    /** term → 文档频次 DF */
    private final Map<String, Integer> docFrequencies = new ConcurrentHashMap<>();
    /** docId → { term → 词频 } */
    private final Map<String, Map<String, Integer>> termFrequencies = new ConcurrentHashMap<>();
    /** 所有文档长度总和 (用于计算 avgdl) */
    private long totalDocLength = 0;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ============ 多粒度分词器 ============

    /**
     * 统一文本规范化（小写 + 全角转半角）。
     */
    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            // 全角空格
            if (c == 12288) {
                sb.append(' ');
                continue;
            }
            // 全角字符（65281-65374）转半角
            if (c >= 65281 && c <= 65374) {
                sb.append((char) (c - 65248));
                continue;
            }
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 简易分词：
     * - 英文/数字：整词保留
     * - 中文：2-gram + 3-gram + 整词（短词）混合
     * 面试说明：生产环境可替换为 jieba/HanLP/ES analyzer，此处为零依赖实现。
     */
    private List<String> tokenize(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            String segment = matcher.group();
            if (segment == null || segment.isBlank()) continue;

            // 英文/数字/字母数字串
            if (segment.matches("[a-z]+\\d*") || segment.matches("\\d+")) {
                if (!STOP_WORDS.contains(segment)) {
                    tokens.add(segment);
                }
                continue;
            }

            // 中文串：2-gram + 3-gram
            if (segment.matches("[\\u4e00-\\u9fa5]+")) {
                int length = segment.length();
                if (length == 1) {
                    if (!STOP_WORDS.contains(segment)) {
                        tokens.add(segment);
                    }
                    continue;
                }

                for (int i = 0; i + 1 < length; i++) {
                    String bi = segment.substring(i, i + 2);
                    if (!STOP_WORDS.contains(bi)) {
                        tokens.add(bi);
                    }
                }
                if (length >= 3) {
                    for (int i = 0; i + 2 < length; i++) {
                        String tri = segment.substring(i, i + 3);
                        if (!STOP_WORDS.contains(tri)) {
                            tokens.add(tri);
                        }
                    }
                }

                // 短中文词整词保留，增强专有词命中（如“学生票”“改签费”）
                if (length <= 8 && !STOP_WORDS.contains(segment)) {
                    tokens.add(segment);
                }
            }
        }

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
            // 若重复 docId，先移除旧索引，避免 DF/长度统计污染
            removeDocumentInternal(docId);

            String normalizedText = normalizeText(text);
            List<String> tokens = tokenize(normalizedText);
            DocumentInfo doc = new DocumentInfo(docId, text, normalizedText, parentId, parentText, tokens);
            documents.put(docId, doc);

            // 构建词频表
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            termFrequencies.put(docId, tf);

            // 更新倒排索引
            for (String term : tf.keySet()) {
                Set<String> postings = invertedIndex.computeIfAbsent(term, k -> ConcurrentHashMap.newKeySet());
                postings.add(docId);
                docFrequencies.put(term, postings.size());
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

            String normalizedQuery = normalizeText(query);
            List<String> queryTokens = tokenize(normalizedQuery);
            if (queryTokens.isEmpty()) {
                return Collections.emptyList();
            }

            // 统计 query term 频次，近似 query tf 加权
            Map<String, Integer> queryTermFrequencies = new HashMap<>();
            for (String token : queryTokens) {
                queryTermFrequencies.merge(token, 1, Integer::sum);
            }

            int totalDocs = documents.size();
            double avgdl = (double) totalDocLength / totalDocs;

            // 收集所有候选文档（至少包含一个 query token 的文档）
            Set<String> candidateDocIds = new HashSet<>();
            for (String token : queryTermFrequencies.keySet()) {
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
                String normalizedDocText = doc.getNormalizedText();
                int phraseMatched = 0;

                int phraseBudget = MAX_PHRASE_BONUS_TERMS;
                for (Map.Entry<String, Integer> entry : queryTermFrequencies.entrySet()) {
                    String token = entry.getKey();
                    int qtf = entry.getValue();
                    int n = docFrequencies.getOrDefault(token, 0);
                    if (n <= 0) continue;

                    // 动态过滤过高文档频次词（近似 stopword）
                    if (((double) n / (double) totalDocs) > HIGH_DF_RATIO) {
                        continue;
                    }

                    // IDF: log((N - n + 0.5) / (n + 0.5) + 1)
                    double idf = Math.log((totalDocs - n + 0.5) / (n + 0.5) + 1.0);

                    // TF 归一化（BM25+）
                    int termFreq = tf.getOrDefault(token, 0);
                    if (termFreq <= 0) continue;

                    double tfNorm = (termFreq * (K1 + 1))
                            / (termFreq + K1 * (1 - B + B * docLen / avgdl));
                    double queryWeight = 1.0 + Math.log1p(qtf);
                    score += idf * (tfNorm + DELTA) * queryWeight;

                    // 对长度>=2 token 做短语命中加分，提升规章术语命中
                    if (phraseBudget > 0 && token.length() >= 2 && normalizedDocText.contains(token)) {
                        phraseMatched++;
                        phraseBudget--;
                    }
                }

                if (phraseMatched > 0) {
                    score += PHRASE_BONUS * phraseMatched;
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

    private void removeDocumentInternal(String docId) {
        DocumentInfo existing = documents.remove(docId);
        if (existing == null) {
            return;
        }

        Map<String, Integer> existingTf = termFrequencies.remove(docId);
        if (existingTf != null) {
            for (String term : existingTf.keySet()) {
                Set<String> postings = invertedIndex.get(term);
                if (postings == null) continue;
                postings.remove(docId);
                if (postings.isEmpty()) {
                    invertedIndex.remove(term);
                    docFrequencies.remove(term);
                } else {
                    docFrequencies.put(term, postings.size());
                }
            }
        }

        totalDocLength -= existing.getTokens().size();
        if (totalDocLength < 0) {
            totalDocLength = 0;
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
            docFrequencies.clear();
            termFrequencies.clear();
            totalDocLength = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
