package com.aidevplatform.knowledge.infrastructure.embedding;

import com.aidevplatform.knowledge.domain.model.KnowledgeChunk;

import java.util.List;

/**
 * Embedding 服务接口（基础设施层 Port）。
 * 职责：批量为 KnowledgeChunk 生成 BGE-M3 向量，填充 chunk.vector 字段。
 * 实现类对接 LangChain4j 或直接调用 Embedding API（HTTP）。
 *
 * <p>调用方（DocumentIngestPipeline）通过此接口调用，不依赖具体实现。
 */
public interface EmbeddingService {

    /**
     * 批量向量化，直接修改传入 chunks 的 vector 字段（就地填充）。
     * 失败时抛 RuntimeException，由 IngestService 决定重试或转 DLQ。
     *
     * @param chunks 待向量化的 chunk 列表，vector 字段在调用后被填充
     */
    void embed(List<KnowledgeChunk> chunks);

    /**
     * 向量化单个文本（用于查询改写、相似度计算等场景）。
     *
     * @param text 待向量化文本
     * @return 1024 维 float 向量
     */
    float[] encode(String text);
}
