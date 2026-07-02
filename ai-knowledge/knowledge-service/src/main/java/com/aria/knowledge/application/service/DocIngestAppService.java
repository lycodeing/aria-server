package com.aria.knowledge.application.service;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.page.PageResult;
import com.aria.common.core.util.IdGenerator;
import com.aria.knowledge.application.query.DocPageQuery;
import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.model.KnowledgeDoc;
import com.aria.knowledge.domain.repository.KnowledgeDocRepository;
import com.aria.knowledge.infrastructure.mq.DocIngestEvent;
import com.aria.knowledge.infrastructure.mq.DocIngestPublisher;
import com.aria.knowledge.infrastructure.storage.MinioStorageService;
import com.aria.knowledge.interfaces.rest.vo.DocStatusVO;
import com.aria.knowledge.interfaces.rest.vo.DocUploadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 文档摄取应用服务。
 * 职责：上传、查状态、分页列表、审核、下线等文档管理用例编排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocIngestAppService {

    // ---- 错误码常量（统一错误码定义，杜绝魔法值） ----
    private static final int    ERROR_INTERNAL          = 500;
    private static final int    ERROR_BAD_REQUEST       = 400;
    private static final int    ERROR_DOC_NOT_FOUND     = 4004;
    /** 文件类型常量（与 MultiFormatParser 分发依据一致） */
    private static final String FILE_TYPE_PDF           = "PDF";
    private static final String FILE_TYPE_HTML          = "HTML";
    private static final String FILE_TYPE_DOCX          = "DOCX";
    private static final String FILE_TYPE_MARKDOWN      = "MARKDOWN";
    /** 内容哈希待计算占位（异步管道处理后回填真实 SHA-256） */
    private static final String CONTENT_HASH_PENDING    = "pending";
    /** 未登录场景的兜底 uploaderId */
    private static final String SYSTEM_USER             = "system";
    /** 上传响应初始状态 */
    private static final String UPLOAD_STATUS_PENDING   = "PENDING";

    private final KnowledgeDocRepository docRepository;
    private final DocIngestPublisher     publisher;
    private final MinioStorageService    minioStorageService;

    // -------------------------------------------------------
    // 上传
    // -------------------------------------------------------

    /**
     * 接收上传文件，文档状态初始为 DRAFT。
     *
     * <p>执行顺序（保证一致性，避免"幽灵消息"）：
     * <ol>
     *   <li>上传文件到 MinIO（无副作用即可，失败直接抛出）</li>
     *   <li>事务内写 DB（DRAFT 状态）</li>
     *   <li>事务提交后通过 {@link TransactionSynchronization#afterCommit} 发布 MQ，
     *       避免 DB 失败时 MQ 已发出导致 Consumer 找不到记录</li>
     * </ol>
     *
     * @param file 上传的文件
     * @param kbId 所属知识库 ID
     * @return 上传结果（含 docId）
     */
    @Transactional(rollbackFor = Exception.class)
    public DocUploadVO submit(MultipartFile file, String kbId) {
        String docId    = String.valueOf(IdGenerator.nextId());
        String fileType = resolveFileType(file.getOriginalFilename());

        // Step 1：上传到 MinIO（事务外副作用，失败抛出由 Controller 处理）
        String storagePath;
        try {
            storagePath = minioStorageService.upload(docId, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(ERROR_INTERNAL, "文件读取失败: " + e.getMessage());
        }

        // Step 2：写入数据库（事务内）
        KnowledgeDoc doc = KnowledgeDoc.builder()
            .id(docId)
            .kbId(kbId)
            .fileName(file.getOriginalFilename())
            .fileType(fileType)
            .storagePath(storagePath)
            .contentHash(CONTENT_HASH_PENDING)
            .status(DocStatus.DRAFT)
            .uploaderId(safeLoginId())
            .build();
        docRepository.save(doc);

        // Step 3：注册事务回调：
        //   afterCommit  → 发布 MQ（保证 Consumer 能查到 DB 记录）
        //   afterRollback → 删除 MinIO 文件（补偿，避免 DB 失败后文件孤立）
        DocIngestEvent event = DocIngestEvent.builder()
            .docId(docId)
            .kbId(kbId)
            .fileType(fileType)
            .storagePath(storagePath)
            .build();
        final String finalStoragePath = storagePath;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.publish(event);
            }

            @Override
            public void afterCompletion(int status) {
                // STATUS_ROLLED_BACK = 1：事务回滚时删除 MinIO 孤立文件
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    log.warn("文档上传事务回滚，清理 MinIO 孤立文件 docId={} path={}", docId, finalStoragePath);
                    try {
                        minioStorageService.delete(finalStoragePath);
                    } catch (Exception ex) {
                        log.error("清理 MinIO 孤立文件失败，需人工处理 docId={} path={}", docId, finalStoragePath, ex);
                    }
                }
            }
        });

        log.info("文档上传接收成功，docId={}，fileType={}，storagePath={}", docId, fileType, storagePath);
        return DocUploadVO.builder()
            .docId(docId)
            .status(UPLOAD_STATUS_PENDING)
            .message("文档已接收，正在后台处理，可通过 docId 查询进度")
            .build();
    }

    // -------------------------------------------------------
    // 查询
    // -------------------------------------------------------

    /**
     * 分页查询文档列表。
     *
     * @param query 分页查询条件（status 已为枚举类型，校验在 Controller 完成）
     * @return 分页结果
     */
    public PageResult<KnowledgeDoc> listDocs(DocPageQuery query) {
        return docRepository.findPage(query);
    }

    /**
     * 查询单个文档摄取进度。
     *
     * @param docId 文档 ID
     * @return 文档状态 VO
     */
    public DocStatusVO getStatus(String docId) {
        return docRepository.findById(docId)
            .map(doc -> DocStatusVO.builder()
                .docId(doc.getId())
                .status(doc.getStatus().name())
                .fileName(doc.getFileName())
                .build())
            .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));
    }

    // -------------------------------------------------------
    // 审核
    // -------------------------------------------------------

    /**
     * 审核文档：通过时将状态推进到 PUBLISHED，退回时推回 DRAFT。
     *
     * @param docId        文档 ID
     * @param approved     true=通过，false=退回
     * @param rejectReason 退回原因（仅 approved=false 时有意义）
     */
    public void review(String docId, boolean approved, String rejectReason) {
        KnowledgeDoc doc = docRepository.findById(docId)
            .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));

        // 状态模式：流转合法性由 DocStatus.transitionTo() 统一校验，非法流转抛出 5010
        DocStatus newStatus = doc.getStatus().transitionTo(
            approved ? DocStatus.PUBLISHED : DocStatus.DRAFT
        );
        String reviewerId = safeLoginId();
        docRepository.updateReview(docId, newStatus, reviewerId);

        log.info("文档审核完成，docId={}，结果={}，reviewerId={}",
            docId, approved ? "通过" : "退回", reviewerId);
    }

    // -------------------------------------------------------
    // 下线
    // -------------------------------------------------------

    /**
     * 下线文档（更新状态为 DEPRECATED，chunk 由 Worker 异步清理）。
     *
     * @param docId 文档 ID
     */
    public void offline(String docId) {
        KnowledgeDoc doc = docRepository.findById(docId)
            .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));
        // 状态模式：DEPRECATED 是终态，DRAFT/FAILED 状态直接下线也应拒绝
        doc.getStatus().transitionTo(DocStatus.DEPRECATED);
        docRepository.updateStatusBatch(List.of(docId), DocStatus.DEPRECATED);
        log.info("文档已下线，docId={}", docId);
    }

    /**
     * 失败文档重试：FAILED → DRAFT，重新发布摄取 MQ 消息。
     * 文件已在 MinIO 中，无需重新上传。
     */
    @Transactional(rollbackFor = Exception.class)
    public void retry(String docId) {
        KnowledgeDoc doc = docRepository.findById(docId)
            .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));
        // 状态模式：仅 FAILED 允许流转到 DRAFT
        doc.getStatus().transitionTo(DocStatus.DRAFT);
        docRepository.updateStatusBatch(List.of(docId), DocStatus.DRAFT);
        DocIngestEvent event = DocIngestEvent.builder()
            .docId(docId)
            .kbId(doc.getKbId())
            .fileType(doc.getFileType())
            .storagePath(doc.getStoragePath())
            .build();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() { publisher.publish(event); }
            });
        log.info("文档重试摄取已触发，docId={}", docId);
    }

    /**
     * 已发布文档重新摄取：不改变状态，直接重新发布 MQ（携带 forceReingest=true）。
     * 适用于解析逻辑升级后需要重新生成 chunk 的场景，pipeline 幂等处理。
     *
     * <p>forceReingest=true 使 IdempotencyCheckHandler 跳过 PUBLISHED 终态校验，
     * 确保摄取管道正常执行而不被 abort 静默丢弃。
     */
    public void reingest(String docId) {
        KnowledgeDoc doc = docRepository.findById(docId)
            .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));
        if (doc.getStatus() != DocStatus.PUBLISHED) {
            throw new BusinessException(ERROR_BAD_REQUEST,
                "只有 PUBLISHED 状态的文档可以重新摄取，当前状态：" + doc.getStatus());
        }
        DocIngestEvent event = DocIngestEvent.builder()
            .docId(docId)
            .kbId(doc.getKbId())
            .fileType(doc.getFileType())
            .storagePath(doc.getStoragePath())
            .forceReingest(true)   // 跳过 IdempotencyCheckHandler 终态校验
            .build();
        publisher.publish(event);
        log.info("文档重新摄取已触发，docId={}", docId);
    }

    /**
     * 批量下线文档（每条单独走状态模式校验，非 PUBLISHED 状态静默跳过）。
     *
     * @param docIds 文档 ID 列表，最多 50 条
     */
    public void batchOffline(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) return;
        if (docIds.size() > 50) {
            throw new BusinessException(ERROR_BAD_REQUEST, "批量操作最多支持 50 条");
        }
        List<String> publishedIds = docIds.stream()
            .map(id -> docRepository.findById(id).orElse(null))
            .filter(doc -> doc != null && doc.getStatus() == DocStatus.PUBLISHED)
            .map(KnowledgeDoc::getId)
            .toList();
        if (!publishedIds.isEmpty()) {
            docRepository.updateStatusBatch(publishedIds, DocStatus.DEPRECATED);
            log.info("批量下线完成，数量={}", publishedIds.size());
        }
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /**
     * 根据文件后缀解析文件类型，与 {@code MultiFormatParser} 分发逻辑一致。
     */
    private String resolveFileType(String fileName) {
        if (fileName == null) {
            return FILE_TYPE_MARKDOWN;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return FILE_TYPE_PDF;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return FILE_TYPE_HTML;
        }
        if (lower.endsWith(".docx")) {
            return FILE_TYPE_DOCX;
        }
        return FILE_TYPE_MARKDOWN;
    }

    /**
     * 安全获取当前登录用户 ID，未登录时返回 {@link #SYSTEM_USER}。
     *
     * <p>阿里规约：精确捕获已知异常（Sa-Token 的 {@link NotLoginException}），
     * 不使用 {@code catch (Exception e)} 吞掉所有异常。
     */
    private String safeLoginId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : SYSTEM_USER;
        } catch (NotLoginException e) {
            return SYSTEM_USER;
        }
    }
}
