package com.aria.common.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("VectorMathUtils 向量数学工具")
class VectorMathUtilsTest {

    @Test
    @DisplayName("normalize: 归一化后模长为1")
    void normalize_resultHasUnitLength() {
        float[] v = {3.0f, 4.0f};  // 模 = 5
        float[] norm = VectorMathUtils.normalize(v);
        double length = Math.sqrt(norm[0] * norm[0] + norm[1] * norm[1]);
        assertThat(length).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("normalize: 不修改原向量")
    void normalize_doesNotMutateInput() {
        float[] v = {3.0f, 4.0f};
        VectorMathUtils.normalize(v);
        assertThat(v[0]).isEqualTo(3.0f);
        assertThat(v[1]).isEqualTo(4.0f);
    }

    @Test
    @DisplayName("normalize: 零向量返回全零，不抛异常")
    void normalize_zeroVector_returnsZero() {
        float[] v = {0.0f, 0.0f};
        float[] result = VectorMathUtils.normalize(v);
        assertThat(result[0]).isEqualTo(0.0f);
        assertThat(result[1]).isEqualTo(0.0f);
    }

    @Test
    @DisplayName("cosineSimilarity: 同向量相似度为1")
    void cosineSimilarity_sameVector_returnsOne() {
        float[] a = VectorMathUtils.normalize(new float[]{1.0f, 0.0f});
        assertThat(VectorMathUtils.cosineSimilarity(a, a)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("cosineSimilarity: 正交向量相似度为0")
    void cosineSimilarity_orthogonal_returnsZero() {
        float[] a = VectorMathUtils.normalize(new float[]{1.0f, 0.0f});
        float[] b = VectorMathUtils.normalize(new float[]{0.0f, 1.0f});
        assertThat(VectorMathUtils.cosineSimilarity(a, b)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("meanAndNormalize: 单向量等于归一化自身")
    void meanAndNormalize_singleVector_equalsNormalized() {
        float[] v = {3.0f, 4.0f};
        float[] result = VectorMathUtils.meanAndNormalize(List.of(v));
        float[] expected = VectorMathUtils.normalize(v);
        for (int i = 0; i < result.length; i++) {
            assertThat((double) result[i]).isCloseTo(expected[i], within(1e-6));
        }
    }

    @Test
    @DisplayName("meanAndNormalize: 多向量均值后归一化，模长为1")
    void meanAndNormalize_multipleVectors_unitLength() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        float[] result = VectorMathUtils.meanAndNormalize(List.of(a, b));
        double length = Math.sqrt(result[0] * result[0] + result[1] * result[1]);
        assertThat(length).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("meanAndNormalize: 空列表抛 IllegalArgumentException")
    void meanAndNormalize_emptyList_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> VectorMathUtils.meanAndNormalize(List.of()));
    }

    @Test
    @DisplayName("cosineSimilarity: 维度不匹配抛 IllegalArgumentException（I3修复验证）")
    void cosineSimilarity_dimensionMismatch_throwsException() {
        float[] a = {1.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        assertThrows(IllegalArgumentException.class,
                () -> VectorMathUtils.cosineSimilarity(a, b));
    }

    @Test
    @DisplayName("meanAndNormalize: 反向量均值为零向量，不抛异常")
    void meanAndNormalize_oppositeVectors_noException() {
        float[] a = {1.0f, 0.0f};
        float[] b = {-1.0f, 0.0f};
        float[] result = VectorMathUtils.meanAndNormalize(List.of(a, b));
        assertThat(result).hasSize(2);
    }
}
