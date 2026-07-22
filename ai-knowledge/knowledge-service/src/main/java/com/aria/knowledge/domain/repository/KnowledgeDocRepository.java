package com.aria.knowledge.domain.repository;

import com.aria.common.core.page.PageResult;
import com.aria.knowledge.application.query.DocPageQuery;
import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.model.KnowledgeDoc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 文档 Repository 接口（域层定义，无任何框架依赖）。
 */
public interface KnowledgeDocRepository {

    void save(KnowledgeDoc doc);

    Optional<KnowledgeDoc> findById(String docId);

    List<KnowledgeDoc> findExpired(LocalDate today);

    /**
     * 分页查询文档列表。
     *
     * @param query 分页查询条件（含关键词、知识库 ID、状态、分页参数）
     * @return 分页结果
     */
    PageResult<KnowledgeDoc> findPage(DocPageQuery query);

    /**
     * 更新文档审核状态及审核人。
     */
    void updateReview(String docId, DocStatus status, String reviewerId);

    void updateStatusBatch(List<String> docIds, DocStatus status);

    /**
     * 批量查询 docId → fileName 映射，消除 searchTest 中逐条查询的 N+1 问题。
     *
     * @param docIds 文档 ID 列表
     * @return docId → fileName 的 Map，不存在的 docId 不在结果中
     */
    java.util.Map<String, String> findFileNamesByIds(List<String> docIds);

    /**
     * 带条件的原子状态更新：仅当文档当前状态为 DRAFT 时才更新为目标状态。
     */
    int updateStatusIfDraft(String docId, DocStatus newStatus);

    /**
     * 带条件的状态更新：仅当文档当前状态匹配 expectedStatus 时才更新为 newStatus。
     * 用于消除 findById + update 两步操作的 TOCTOU 竞态。
     *
     * @return 影响行数，0 表示文档不存在或状态已变更
     */
    int updateStatusIf(String docId, DocStatus expectedStatus, DocStatus newStatus);

    /**
     * 带条件的审核状态更新：仅当文档当前状态匹配 expectedStatus 时才写入审核结果。
     * 用于消除 review() 中读-校验-写的并发竞态。
     *
     * @return 影响行数，0 表示文档不存在或状态已变更
     */
    int updateReviewIfStatus(String docId, DocStatus expectedStatus, DocStatus newStatus, String reviewerId);

    /**
     * 原子替换文档版本：将旧文档下线（DEPRECATED）、新文档上线（PUBLISHED）。
     * 必须在同一事务内完成，实现类负责事务保证。
     */
    void atomicSwap(String oldDocId, String newDocId);
}

