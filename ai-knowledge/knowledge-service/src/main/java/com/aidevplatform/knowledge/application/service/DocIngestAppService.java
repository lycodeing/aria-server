package com.aidevplatform.knowledge.application.service;

import cn.dev33.satoken.stp.StpUtil;
import com.aidevplatform.common.core.exception.BusinessException;
import com.aidevplatform.common.core.page.PageResult;
import com.aidevplatform.common.core.util.IdGenerator;
import com.aidevplatform.common.web.redis.RedisStreamHelper;
import com.aidevplatform.knowledge.domain.model.DocStatus;
import com.aidevplatform.knowledge.domain.model.KnowledgeDoc;
import com.aidevplatform.knowledge.domain.repository.KnowledgeDocRepository;
import com.aidevplatform.knowledge.application.query.DocPageQuery;
import com.aidevplatform.knowledge.infrastructure.mq.DocIngestEvent;
import com.aidevplatform.knowledge.infrastructure.storage.MinioStorageService;
import com.aidevplatform.knowledge.interfaces.rest.vo.DocStatusVO;
import com.aidevplatform.knowledge.interfaces.rest.vo.DocUploadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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

    private final KnowledgeDocRepository docRepository;
    private final RedisStreamHelper       streamHelper;
    private final MinioStorageService     minioStorageService;

    @Value("${knowledge.ingest.stream-key}")
    private String streamKey;

    // -------------------------------------------------------
    // 上传
    // -------------------------------------------------------

    /**
     * 接收上传文件，写 Redis Streams，立即返回 202 Accepted。
     * 文档状态初始为 DRAFT，Worker 处理完成后更新为 PUBLISHED。
     *
     * @param file 上传的文件
     * @param kbId 所属知识库 ID
     * @return 上传结果（含 docId）
     */
    public DocUploadVO submit(MultipartFile file, String kbId) {
        String docId    = String.valueOf(IdGenerator.nextId());
        String fileType = resolveFileType(file.getOriginalFilename());

        // Step 1：上传文件到 MinIO，获得真实存储路径
        String storagePath;
        try {
            storagePath = minioStorageService.upload(docId, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(500, "文件读取失败: " + e.getMessage());
        }

        // Step 2：发布摄取事件到 Redis Streams
        DocIngestEvent event = DocIngestEvent.builder()
            .docId(docId)
            .kbId(kbId)
            .fileType(fileType)
            .storagePath(storagePath)
            .build();
        streamHelper.publish(streamKey, event.toPayload());

        // Step 3：写入数据库（使用真实 storagePath）
        KnowledgeDoc doc = KnowledgeDoc.builder()
            .id(docId)
            .kbId(kbId)
            .fileName(file.getOriginalFilename())
            .fileType(fileType)
            .storagePath(storagePath)
            .contentHash("pending")
            .status(DocStatus.DRAFT)
            .uploaderId(safeLoginId())
            .build();
        docRepository.save(doc);

        log.info("文档上传接收成功，docId={}，fileType={}，storagePath={}", docId, fileType, storagePath);
        return DocUploadVO.builder()
            .docId(docId)
            .status("PENDING")
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
            .orElseThrow(() -> new BusinessException(4004, "文档不存在：" + docId));
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
            .orElseThrow(() -> new BusinessException(4004, "文档不存在：" + docId));

        if (doc.getStatus() != DocStatus.DRAFT && doc.getStatus() != DocStatus.REVIEW) {
            throw new BusinessException(400,
                "只有 DRAFT 或 REVIEW 状态的文档才能审核，当前状态：" + doc.getStatus());
        }

        DocStatus newStatus = approved ? DocStatus.PUBLISHED : DocStatus.DRAFT;
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
        docRepository.findById(docId)
            .orElseThrow(() -> new BusinessException(4004, "文档不存在：" + docId));
        docRepository.updateStatusBatch(List.of(docId), DocStatus.DEPRECATED);
        log.info("文档已下线，docId={}", docId);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    private String resolveFileType(String fileName) {
        if (fileName == null) {
            return "MARKDOWN";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))                             return "PDF";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML";
        if (lower.endsWith(".docx"))                           return "DOCX";
        return "MARKDOWN";
    }

    /**
     * 安全获取当前登录用户 ID，未登录时返回 "system"。
     */
    private String safeLoginId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : "system";
        } catch (Exception e) {
            return "system";
        }
    }
}
