package com.aria.common.core.util;

import java.util.List;

/**
 * 向量数学运算工具类。
 *
 * <p>与 {@link VectorUtils}（格式转换）的职责分离：
 * 本类负责向量的数学操作（归一化、余弦相似度、均值），
 * {@link VectorUtils} 负责 float[] 与 pgvector 字符串格式互转。
 */
public final class VectorMathUtils {

    private VectorMathUtils() {}

    /**
     * 计算多个向量的均值并进行 L2 归一化，用于构建意图原型向量。
     *
     * @param vectors 输入向量列表，不可为空
     * @return 均值后 L2 归一化的向量；若均值为零向量则返回全零向量
     * @throws IllegalArgumentException 若 vectors 为空或 null
     */
    public static float[] meanAndNormalize(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalArgumentException("vectors 列表不能为空");
        }
        int dim = vectors.get(0).length;
        float[] mean = new float[dim];
        for (float[] v : vectors) {
            for (int i = 0; i < dim; i++) {
                mean[i] += v[i];
            }
        }
        for (int i = 0; i < dim; i++) {
            mean[i] /= vectors.size();
        }
        return normalize(mean);
    }

    /**
     * 对向量进行 L2 归一化（使向量模长为 1）。
     *
     * @param v 输入向量
     * @return 归一化后的新向量（不修改原向量）；若模为 0 则返回全零向量
     */
    public static float[] normalize(float[] v) {
        double norm = 0.0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        float[] result = new float[v.length];
        if (norm < 1e-10) {
            return result;  // 零向量，返回全零
        }
        for (int i = 0; i < v.length; i++) {
            result[i] = (float) (v[i] / norm);
        }
        return result;
    }

    /**
     * 计算两个已归一化向量的余弦相似度（即点积）。
     *
     * <p><b>前置条件：</b>入参向量必须已经 L2 归一化，此时余弦相似度等于点积，计算更高效。
     *
     * @param a 已归一化向量 a
     * @param b 已归一化向量 b
     * @return 余弦相似度，范围 [-1.0, 1.0]
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
