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
     * 批量物理删除多个文档的所有 chunk（过期批量下线时调用）。
     * 单条 WHERE doc_id IN (...) 替代 N 次单文档删除，消除 N+1 问题。
     *
     * @param docIds 文档 ID 列表，不得为空
     */
    void deleteByDocIds(List<String> docIds);

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

    /**
     * 按文档 ID 聚合 chunk 统计（DB 端聚合，避免全量加载到内存）。
     * 返回 Map 包含：totalChunks、totalTokens、textChunks、tableChunks、imageChunks。
     *
     * @param docId 文档 ID
     */
    java.util.Map<String, Long> countStatsByDocId(String docId);

    /** 更新 chunk 的检索权重（0.0=禁用，1.0=启用） */
    void updateWeight(String chunkId, java.math.BigDecimal weight);

    /**
     * 原子更新 chunk 内容、token 数和向量（单次 UPDATE，替代两步操作）。
     * 由 KnowledgeChunkAppService.updateContent 调用，保证内容与向量始终同步。
     *
     * @param chunkId    chunk ID
     * @param content    新内容文本
     * @param tokenCount 新 token 数
     * @param vectorStr  新向量字符串（pgvector 格式）
     */
    void updateContentAndVector(String chunkId, String content, int tokenCount, String vectorStr);

    /** 保存单个新 chunk（手动添加 Q&A 场景） */
    void save(KnowledgeChunk chunk);

    /**
     * 按知识库 ID 汇总 Chunk 统计（仅统计 PUBLISHED 且权重 > 0 的）。
     * @return Map 包含 chunkCount 和 tokenSum
     */
    java.util.Map<String, Long> countStatsByKbId(String kbId);
}
