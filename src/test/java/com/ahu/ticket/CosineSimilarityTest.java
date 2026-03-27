package com.ahu.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语义缓存核心算法测试 —— 余弦相似度
 *
 * 面试话术要点：
 * - 余弦相似度是语义缓存命中判定的数学基础
 * - 公式: cos(A, B) = (A · B) / (|A| × |B|)
 * - 阈值 0.95 表示"极高相似"，防止语义篡改
 * - 验证边界条件：相同/正交/反向向量
 */
public class CosineSimilarityTest {

    /**
     * 与 RagServiceImpl 中的 cosineSimilarity 方法完全一致
     */
    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Test
    @DisplayName("相同向量 → 相似度 = 1.0（完全一致）")
    void testIdenticalVectors() {
        float[] v = {1.0f, 2.0f, 3.0f};
        double similarity = cosineSimilarity(v, v);
        assertEquals(1.0, similarity, 1e-6, "相同向量的余弦相似度必须为 1.0");
        System.out.println("✅ 相同向量: similarity = " + similarity);
    }

    @Test
    @DisplayName("正交向量 → 相似度 = 0.0（完全不相关）")
    void testOrthogonalVectors() {
        float[] v1 = {1.0f, 0.0f, 0.0f};
        float[] v2 = {0.0f, 1.0f, 0.0f};
        double similarity = cosineSimilarity(v1, v2);
        assertEquals(0.0, similarity, 1e-6, "正交向量的余弦相似度必须为 0.0");
        System.out.println("✅ 正交向量: similarity = " + similarity);
    }

    @Test
    @DisplayName("反向向量 → 相似度 = -1.0（完全相反）")
    void testOppositeVectors() {
        float[] v1 = {1.0f, 2.0f, 3.0f};
        float[] v2 = {-1.0f, -2.0f, -3.0f};
        double similarity = cosineSimilarity(v1, v2);
        assertEquals(-1.0, similarity, 1e-6, "反向向量的余弦相似度必须为 -1.0");
        System.out.println("✅ 反向向量: similarity = " + similarity);
    }

    @Test
    @DisplayName("高相似向量 → 超过 0.95 阈值 → 语义缓存命中")
    void testSemanticCacheHit() {
        float[] cachedVector = {0.9f, 0.8f, 0.7f, 0.6f, 0.5f};
        float[] queryVector = {0.91f, 0.79f, 0.71f, 0.59f, 0.51f}; // 微小扰动
        double similarity = cosineSimilarity(cachedVector, queryVector);

        assertTrue(similarity > 0.95,
                "微小扰动的向量相似度应 > 0.95，触发语义缓存命中。实际值: " + similarity);
        System.out.println("✅ 语义缓存命中测试通过！similarity = " + String.format("%.6f", similarity));
    }

    @Test
    @DisplayName("低相似向量 → 低于 0.95 阈值 → 语义缓存未命中")
    void testSemanticCacheMiss() {
        float[] cachedVector = {0.9f, 0.8f, 0.7f, 0.6f, 0.5f};
        float[] queryVector = {0.1f, 0.9f, 0.2f, 0.8f, 0.3f}; // 显著差异
        double similarity = cosineSimilarity(cachedVector, queryVector);

        assertTrue(similarity < 0.95,
                "差异较大的向量相似度应 < 0.95，不触发缓存。实际值: " + similarity);
        System.out.println("✅ 语义缓存未命中测试通过！similarity = " + String.format("%.6f", similarity));
    }

    @Test
    @DisplayName("高维向量余弦相似度计算正确性（模拟 Embedding 维度）")
    void testHighDimensionalVectors() {
        int dim = 1024; // 常见 Embedding 维度
        float[] v1 = new float[dim];
        float[] v2 = new float[dim];

        // 生成两个近似的高维向量
        for (int i = 0; i < dim; i++) {
            v1[i] = (float) Math.sin(i * 0.1);
            v2[i] = (float) Math.sin(i * 0.1) + 0.001f; // 极小扰动
        }

        double similarity = cosineSimilarity(v1, v2);
        assertTrue(similarity > 0.999, "极小扰动的 1024 维向量应高度相似。实际值: " + similarity);
        System.out.println("✅ 高维向量测试通过！dim=" + dim + ", similarity = " + String.format("%.6f", similarity));
    }
}
