package com.aidevplatform.knowledge.domain.model;

import lombok.Builder;
import lombok.Data;
import lombok.With;

/**
 * Chunk 检索命中结果（值对象）。
 * 包含 chunk 内容和检索相关性分数，用于 RAG 管道的中间传递。
 */
@Data
@Builder
@With
public class ChunkHit {
    private String chunkId;
    private String docId;
    private String content;
    /** 面包屑路径，用于溯源展示 */
    private String breadcrumb;
    /** 父 chunk ID，用于 Parent-Child 回溯完整上下文 */
    private String parentChunkId;
    /** 相关性分数（RRF 融合分或 Reranker 分），越高越相关 */
    private double score;
    /** 召回来源：VECTOR=向量检索 / FULL_TEXT=全文检索 / RERANK=精排后 */
    private HitSource source;

    public enum HitSource {
        VECTOR, FULL_TEXT, RERANK
    }
}
