package com.aria.common.core.util;

import java.util.*;

/**
 * Reciprocal Rank Fusion 工具类。
 * 将多路检索结果按 RRF 算法融合排序，score(d) = Σ 1/(K + rank_i(d))，K=60 为业界通用默认值。
 */
public final class RrfUtils {

    /** RRF 平滑参数，业界通用 60 */
    private static final int DEFAULT_K = 60;

    private RrfUtils() {}

    /**
     * 融合多路检索结果（使用默认 K=60）。
     *
     * @param topK  最终保留条数
     * @param lists 多路检索结果列表（每个 list 内元素有 chunkId 和 score）
     * @return RRF 融合后按相关性降序排列的 chunkId 列表
     */
    public static List<String> fuse(int topK, List<List<String>> lists) {
        return fuse(DEFAULT_K, topK, lists);
    }

    /**
     * 融合多路检索结果（自定义 K 参数）。
     *
     * <p>参数顺序说明：topK 在前、k 在后，与主入口 {@link #fuse(int, List)} 保持一致，
     * 降低调用方混淆两个 int 参数顺序的风险。
     *
     * @param topK  最终保留条数
     * @param k     RRF 平滑参数（默认 60）
     * @param lists 多路检索结果（每个子列表按相关性降序排列，元素为 chunkId）
     * @return RRF 融合后的 chunkId 列表
     */
    public static List<String> fuseWithK(int topK, int k, List<List<String>> lists) {
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        for (List<String> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                String chunkId = list.get(i);
                scoreMap.merge(chunkId, 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scoreMap.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(Map.Entry::getKey)
            .toList();
    }

    /**
     * @deprecated 参数顺序易混淆（k 在前、topK 在后），请改用 {@link #fuse(int, List)} 或
     *             {@link #fuseWithK(int, int, List)}。
     */
    @Deprecated
    public static List<String> fuse(int k, int topK, List<List<String>> lists) {
        return fuseWithK(topK, k, lists);
    }
}
