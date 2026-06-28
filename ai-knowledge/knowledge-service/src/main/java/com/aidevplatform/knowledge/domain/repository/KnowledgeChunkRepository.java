package com.aidevplatform.knowledge.domain.repository;

import com.aidevplatform.knowledge.domain.model.ChunkHit;
import com.aidevplatform.knowledge.domain.model.KnowledgeChunk;

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
}
