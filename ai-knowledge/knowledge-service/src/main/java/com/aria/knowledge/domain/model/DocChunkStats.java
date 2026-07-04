package com.aria.knowledge.domain.model;

/**
 * 文档级 Chunk 统计值对象。
 *
 * @param totalChunks chunk 总数
 * @param totalTokens token 总量
 * @param textChunks  文本类型 chunk 数
 * @param tableChunks 表格类型 chunk 数
 * @param imageChunks 图片描述类型 chunk 数
 */
public record DocChunkStats(
        long totalChunks,
        long totalTokens,
        long textChunks,
        long tableChunks,
        long imageChunks
) {
    public static final DocChunkStats EMPTY = new DocChunkStats(0, 0, 0, 0, 0);
}
