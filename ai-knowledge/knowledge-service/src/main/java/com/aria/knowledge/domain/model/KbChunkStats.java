package com.aria.knowledge.domain.model;

/**
 * 知识库级 Chunk 汇总统计值对象。
 *
 * @param kbId       知识库 ID
 * @param chunkCount 已发布且权重 > 0 的 chunk 总数
 * @param tokenSum   对应 chunk 的 token 总量
 */
public record KbChunkStats(
        String kbId,
        long chunkCount,
        long tokenSum
) {
    public static KbChunkStats empty(String kbId) {
        return new KbChunkStats(kbId, 0L, 0L);
    }
}
