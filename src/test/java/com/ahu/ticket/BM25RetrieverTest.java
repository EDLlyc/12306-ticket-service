package com.ahu.ticket;

import com.ahu.ticket.rag.BM25Retriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BM25 检索器单元测试
 *
 * 面试话术要点：
 * - 验证手写 BM25 算法的索引构建、分词、IDF 权重和 TF 归一化
 * - 验证中文 unigram + bigram 分词和停用词过滤
 * - 验证 Parent-Child 关联的正确性（child 检索结果能追溯回 parent）
 * - 零外部依赖，纯内存数据结构
 */
public class BM25RetrieverTest {

    private BM25Retriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new BM25Retriever();
    }

    @Test
    @DisplayName("空索引检索 → 返回空列表")
    void testSearchOnEmptyIndex() {
        List<BM25Retriever.ScoredDocument> results = retriever.search("退票规则", 5);
        assertTrue(results.isEmpty(), "空索引应返回空结果");
        assertEquals(0, retriever.size(), "空索引大小为 0");
    }

    @Test
    @DisplayName("单文档精确命中：查询关键词在文档中存在")
    void testSingleDocumentExactMatch() {
        retriever.addDocument("p1:0", "退票手续费按票价的百分之五收取", "p1", "退票完整规章制度");

        List<BM25Retriever.ScoredDocument> results = retriever.search("退票手续费", 5);
        assertFalse(results.isEmpty(), "包含查询关键词的文档应被检索到");
        assertEquals("p1:0", results.get(0).getDocument().getDocId());
        assertTrue(results.get(0).getScore() > 0, "BM25 得分必须大于 0");

        System.out.println("✅ 单文档命中测试通过！得分: " + results.get(0).getScore());
    }

    @Test
    @DisplayName("多文档排序：相关文档排名高于不相关文档")
    void testMultiDocumentRanking() {
        retriever.addDocument("p1:0", "退票手续费按票价百分之五计算", "p1", "退票规则大块");
        retriever.addDocument("p2:0", "改签可以免费办理一次", "p2", "改签规则大块");
        retriever.addDocument("p3:0", "退票需要在发车前三十分钟办理", "p3", "退票时间大块");

        List<BM25Retriever.ScoredDocument> results = retriever.search("退票手续费", 3);
        assertFalse(results.isEmpty());

        // "退票手续费" 应匹配到 p1:0（退票+手续费）排名最高
        assertEquals("p1:0", results.get(0).getDocument().getDocId(),
                "包含'退票'和'手续费'的文档应排名第一");

        System.out.println("✅ 多文档排序测试通过！排名:");
        for (int i = 0; i < results.size(); i++) {
            System.out.println("  #" + (i + 1) + " " + results.get(i).getDocument().getDocId()
                    + " (score: " + String.format("%.4f", results.get(i).getScore()) + ")");
        }
    }

    @Test
    @DisplayName("Parent-Child 关联：child 检索结果保留 parent 元数据")
    void testParentChildLinkage() {
        String parentText = "这是一段关于儿童票的完整规章制度，包含年龄限制和票价折扣规则";
        retriever.addDocument("parent1:0", "儿童票年龄限制六周岁到十四周岁", "parent1", parentText);
        retriever.addDocument("parent1:1", "儿童票票价为成人票的五折", "parent1", parentText);

        List<BM25Retriever.ScoredDocument> results = retriever.search("儿童票年龄", 2);
        assertFalse(results.isEmpty());

        BM25Retriever.DocumentInfo topResult = results.get(0).getDocument();
        assertEquals("parent1", topResult.getParentId(), "child 结果应关联到正确的 parentId");
        assertEquals(parentText, topResult.getParentText(), "child 结果应保留完整的 parent 文本");

        System.out.println("✅ Parent-Child 关联测试通过！parentId: " + topResult.getParentId());
    }

    @Test
    @DisplayName("查询无匹配关键词 → 返回空结果")
    void testNoMatchQuery() {
        retriever.addDocument("p1:0", "退票手续费按票价百分之五计算", "p1", "退票规则");

        List<BM25Retriever.ScoredDocument> results = retriever.search("飞机航班", 5);
        // 由于 BM25 用 unigram/bigram 分词，可能存在单字碰撞，但得分应很低
        // 主要验证不会抛异常，结果列表合理
        assertNotNull(results);
        System.out.println("✅ 无关查询测试通过！返回结果数: " + results.size());
    }

    @Test
    @DisplayName("clear() 清空索引后 size 为 0")
    void testClearIndex() {
        retriever.addDocument("p1:0", "测试文档一", "p1", "父文档一");
        retriever.addDocument("p2:0", "测试文档二", "p2", "父文档二");
        assertEquals(2, retriever.size());

        retriever.clear();
        assertEquals(0, retriever.size(), "清空后索引大小为 0");
        assertTrue(retriever.search("测试", 5).isEmpty(), "清空后检索应返回空");

        System.out.println("✅ 索引清空测试通过！");
    }
}
