package com.ahu.ticket;

import com.ahu.ticket.rag.ZhipuReranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reranker 重排模型的防御性逻辑单元测试
 *
 * 面试话术要点：
 * - Reranker 作为最后的精排漏斗，必须具有极强的鲁棒性
 * - 测试覆盖空输入处理、快速短路、降级兜底等防御性编程
 * - 不调用真正的智谱 API（无 apiKey），验证的是代码逻辑而非网络调用
 */
public class ZhipuRerankerTest {

    private ZhipuReranker reranker;

    @BeforeEach
    void setUp() {
        // 不设置 apiKey，仅测试防御性逻辑（不会调通真实 API）
        reranker = new ZhipuReranker();
    }

    @Test
    @DisplayName("空列表输入 → 返回空列表，不抛异常")
    void testEmptyInput() {
        List<String> result = reranker.rerank("退票规则", Collections.emptyList(), 3);
        assertNotNull(result, "结果不应为 null");
        assertTrue(result.isEmpty(), "空输入应返回空列表");
        System.out.println("✅ 空输入防御测试通过！");
    }

    @Test
    @DisplayName("null 输入 → 返回空列表，不抛异常")
    void testNullInput() {
        List<String> result = reranker.rerank("退票规则", null, 3);
        assertNotNull(result);
        assertTrue(result.isEmpty(), "null 输入应返回空列表");
        System.out.println("✅ null 输入防御测试通过！");
    }

    @Test
    @DisplayName("候选数 ≤ topK → 直接原样返回，跳过精排")
    void testShortCircuitWhenCandidatesLessThanTopK() {
        List<String> candidates = List.of("文档A", "文档B");

        // topK = 3，候选只有 2 个 → 不需要精排
        List<String> result = reranker.rerank("退票", candidates, 3);
        assertEquals(2, result.size(), "候选数 < topK 时应原样返回");
        assertEquals("文档A", result.get(0));
        assertEquals("文档B", result.get(1));

        System.out.println("✅ 短路优化测试通过！跳过不必要的精排。");
    }

    @Test
    @DisplayName("候选数 = topK → 直接原样返回")
    void testShortCircuitWhenCandidatesEqualTopK() {
        List<String> candidates = List.of("文档A", "文档B", "文档C");
        List<String> result = reranker.rerank("退票", candidates, 3);
        assertEquals(3, result.size(), "候选数 = topK 时应原样返回");
        System.out.println("✅ 候选数恰好等于 topK 测试通过！");
    }

    @Test
    @DisplayName("候选数 > topK 且 API 不可用 → 降级返回粗排 Top-K")
    void testGracefulDegradationWhenAPIUnavailable() {
        // 没有设置 apiKey，API 调用必然失败
        // Reranker 的异常捕获应该降级返回前 topK 个粗排结果
        List<String> candidates = new ArrayList<>();
        candidates.add("退票需要在发车前办理");
        candidates.add("改签免费一次");
        candidates.add("儿童票半价优惠");
        candidates.add("行李超重需要另行购票");
        candidates.add("老年人优先购票");

        List<String> result = reranker.rerank("退票规则是什么", candidates, 3);
        assertNotNull(result);
        // 降级后应该返回前 3 个
        assertEquals(3, result.size(), "API 不可用时，降级返回粗排 Top-K");

        System.out.println("✅ API 不可用降级测试通过！降级结果数: " + result.size());
    }
}
