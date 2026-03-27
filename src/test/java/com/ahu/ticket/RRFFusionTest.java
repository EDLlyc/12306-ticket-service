package com.ahu.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RRF (Reciprocal Rank Fusion) 倒数排位融合算法单元测试
 *
 * 面试话术要点：
 * - RRF 公式: score(d) = Σ 1 / (k + rank_i(d))，k=60 是业界标准平滑常数
 * - 解决 Dense（向量语义）和 Sparse（BM25 关键词）两路检索分数不可比的问题
 * - 两路都认可的文档会获得更高的融合分数（排名叠加效应）
 *
 * 本测试独立复制 HybridParentChildContentRetriever 中的 RRF 逻辑，
 * 验证融合算法的数学正确性，零外部依赖。
 */
public class RRFFusionTest {

    private static final int RRF_K = 60; // 与生产代码一致

    /**
     * 模拟 RRF 融合条目，对应 HybridParentChildContentRetriever.FusionEntry
     */
    private static class FusionEntry {
        final String parentId;
        double rrfScore = 0.0;

        FusionEntry(String parentId) {
            this.parentId = parentId;
        }

        void addRRFScore(int rank) {
            this.rrfScore += 1.0 / (RRF_K + rank);
        }
    }

    @Test
    @DisplayName("RRF 公式验证：手动计算 1/(60+rank) 与代码结果一致")
    void testRRFFormulaCorrectness() {
        FusionEntry entry = new FusionEntry("doc-A");

        // 在 Dense 通道排名第 1
        entry.addRRFScore(1);
        double expectedAfterDense = 1.0 / (60 + 1); // 1/61 ≈ 0.016393
        assertEquals(expectedAfterDense, entry.rrfScore, 1e-9, "单路 RRF 分数计算错误");

        // 在 Sparse 通道排名第 3
        entry.addRRFScore(3);
        double expectedAfterBoth = expectedAfterDense + 1.0 / (60 + 3); // 1/61 + 1/63
        assertEquals(expectedAfterBoth, entry.rrfScore, 1e-9, "双路 RRF 融合分数计算错误");

        System.out.println("✅ RRF 公式验证通过！score = " + String.format("%.6f", entry.rrfScore));
    }

    @Test
    @DisplayName("两路都命中的文档 → 融合分数高于单路命中的文档")
    void testDualChannelBoost() {
        // 模拟 Dense 通道结果: [A(rank=1), B(rank=2), C(rank=3)]
        List<String> denseRanking = List.of("A", "B", "C");
        // 模拟 Sparse 通道结果: [B(rank=1), D(rank=2), A(rank=3)]
        List<String> sparseRanking = List.of("B", "D", "A");

        // RRF 融合
        Map<String, FusionEntry> fusionMap = new LinkedHashMap<>();

        for (int rank = 0; rank < denseRanking.size(); rank++) {
            String id = denseRanking.get(rank);
            fusionMap.computeIfAbsent(id, FusionEntry::new).addRRFScore(rank + 1);
        }
        for (int rank = 0; rank < sparseRanking.size(); rank++) {
            String id = sparseRanking.get(rank);
            fusionMap.computeIfAbsent(id, FusionEntry::new).addRRFScore(rank + 1);
        }

        List<FusionEntry> sorted = fusionMap.values().stream()
                .sorted((a, b) -> Double.compare(b.rrfScore, a.rrfScore))
                .collect(Collectors.toList());

        // B 在两路中都出现（Dense rank=2, Sparse rank=1）→ 融合分最高
        assertEquals("B", sorted.get(0).parentId, "双路都命中的文档 B 应排名第一");

        // A 也在两路中都出现（Dense rank=1, Sparse rank=3）→ 排名第二
        assertEquals("A", sorted.get(1).parentId, "双路都命中的文档 A 应排名第二");

        // C 和 D 只出现在单路中
        double singleChannelScore = sorted.get(sorted.size() - 1).rrfScore;
        assertTrue(sorted.get(0).rrfScore > singleChannelScore,
                "双路命中的融合分必须高于单路命中");

        System.out.println("✅ RRF 双路叠加效应验证通过！排序:");
        for (FusionEntry e : sorted) {
            System.out.println("  " + e.parentId + " → score: " + String.format("%.6f", e.rrfScore));
        }
    }

    @Test
    @DisplayName("单通道场景 → 退化为普通倒数排名")
    void testSingleChannelDegradation() {
        List<String> denseOnly = List.of("X", "Y", "Z");

        Map<String, FusionEntry> fusionMap = new LinkedHashMap<>();
        for (int rank = 0; rank < denseOnly.size(); rank++) {
            String id = denseOnly.get(rank);
            fusionMap.computeIfAbsent(id, FusionEntry::new).addRRFScore(rank + 1);
        }

        List<FusionEntry> sorted = fusionMap.values().stream()
                .sorted((a, b) -> Double.compare(b.rrfScore, a.rrfScore))
                .collect(Collectors.toList());

        // 排名应保持原序
        assertEquals("X", sorted.get(0).parentId);
        assertEquals("Y", sorted.get(1).parentId);
        assertEquals("Z", sorted.get(2).parentId);

        // 验证分数递减
        assertTrue(sorted.get(0).rrfScore > sorted.get(1).rrfScore);
        assertTrue(sorted.get(1).rrfScore > sorted.get(2).rrfScore);

        System.out.println("✅ 单通道退化测试通过！RRF 退化为标准倒数排名。");
    }

    @Test
    @DisplayName("k=60 平滑常数的抗极端排名能力：排名 1 和排名 100 的分差不应过于悬殊")
    void testSmoothingEffect() {
        double scoreRank1 = 1.0 / (RRF_K + 1);    // 1/61
        double scoreRank100 = 1.0 / (RRF_K + 100); // 1/160

        double ratio = scoreRank1 / scoreRank100;

        // k=60 使得 rank=1 和 rank=100 的分差比约为 160/61 ≈ 2.6，而非 100 倍
        assertTrue(ratio < 3.0, "k=60 的平滑效果：排名差距 100 倍的文档分数比应 < 3 倍。实际比值: " + ratio);

        System.out.println("✅ 平滑常数验证通过！rank1/rank100 score ratio = " + String.format("%.2f", ratio));
    }
}
