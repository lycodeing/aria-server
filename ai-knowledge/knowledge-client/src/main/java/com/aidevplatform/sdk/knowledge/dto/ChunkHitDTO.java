package com.aidevplatform.sdk.knowledge.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Chunk 命中结果 DTO（knowledge-sdk 对外暴露）。
 */
@Data
@Builder
public class ChunkHitDTO {
    private String chunkId;
    private String docId;
    private String content;
    /** 面包屑路径，如：产品手册 > 定价说明 */
    private String breadcrumb;
    private String parentChunkId;
    /** RRF 融合分或 Reranker 分，越高越相关 */
    private double score;
    /** 召回来源：VECTOR / FULL_TEXT / RERANK */
    private String source;
}
