package com.aria.knowledge.domain.repository;

import com.aria.knowledge.domain.model.ChunkHit;
import com.aria.knowledge.domain.model.KnowledgeChunk;

import java.util.List;

/**
 * Chunk Repository 接口（域层定义，无框架依赖）。
 * 基础设施层的 KnowledgeChunkRepositoryImpl 实现此接口。
 */
public interface KnowledgeChunkRepository {

    /**
     * 批量写入 chunk（upsert 语义，冲突时覆盖）。
     * kbId / docStatus 冗余字段必须在 KnowledgeChunk 中已赋值。
     */
    void saveAll(List<KnowledgeChunk> chunks);

    /** 物理删除指定文档的所有 chunk（文档下线/更新时调用） */
    void deleteByDocId(String docId);

    /**
     * 向量相似度检索（pgvector 余弦距离）。
     * 内部通过 VectorUtils.toStr(queryVector) 转换后调用 XML Mapper。
     */
    List<ChunkHit> vectorSearch(float[] queryVector, int topK, String kbId);

    /**
     * 全文检索（PostgreSQL TF-IDF，依赖 pg_jieba 扩展）。
     */
    List<ChunkHit> fullTextSearch(String query, int topK, String kbId);

    /**
     * 按 ID 查询单个 chunk（Parent-Child 回溯时使用）。
     */
    KnowledgeChunk findById(String chunkId);

    /**
     * 按文档 ID 查询该文档所有 chunk，按页码和 id 排序。
     * 用于文档解析详情展示，不含向量字段。
     */
    List<KnowledgeChunk> findByDocId(String docId);

    /** 更新 chunk 的检索权重（0.0=禁用，1.0=启用） */
    void updateWeight(String chunkId, java.math.BigDecimal weight);

    /** 更新 chunk 内容文本及 token 数（向量由调用方负责重新 embed） */
    void updateContent(String chunkId, String content, Integer tokenCount);

    /** 更新 chunk 向量（编辑内容后重新向量化时调用） */
    void updateVector(String chunkId, String vectorStr);

    /** 保存单个新 chunk（手动添加 Q&A 场景） */
    void save(KnowledgeChunk chunk);

    /**
     * 按知识库 ID 汇总 Chunk 统计（仅统计 PUBLISHED 且权重 > 0 的）。
     * @return Map 包含 chunkCount 和 tokenSum
     */
    java.util.Map<String, Long> countStatsByKbId(String kbId);
}
