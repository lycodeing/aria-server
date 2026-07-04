package com.aria.knowledge.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.knowledge.domain.model.DocChunkStats;
import com.aria.knowledge.domain.model.KbChunkStats;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.domain.model.KnowledgeDoc;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.domain.repository.KnowledgeDocRepository;
import com.aria.knowledge.infrastructure.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库文档查询应用服务。
 *
 * <p>负责文档详情、Chunk 解析内容、统计数据、文档预览等只读查询用例。
 * 将 Controller 中直接操作 Domain Repository 和 Infrastructure 的逻辑
 * 下沉到 Application 层，遵循 DDD 分层规范。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocQueryAppService {

    private static final int ERROR_DOC_NOT_FOUND = 4004;

    private final KnowledgeDocRepository   docRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final MinioStorageService      minioStorageService;

    /**
     * 查询文档的所有 Chunk 解析详情（页码、章节、类型、内容）。
     *
     * @param docId 文档 ID
     * @return chunk 列表，文档不存在时抛出 BusinessException
     */
    public List<KnowledgeChunk> getChunks(String docId) {
        // 先校验文档存在
        docRepository.findById(docId)
                .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));
        return chunkRepository.findByDocId(docId);
    }

    /**
     * 查询文档 chunk 统计（总数、各类型数量、总 token）。
     *
     * @param docId 文档 ID
     * @return 文档级 chunk 统计值对象
     */
    public DocChunkStats getDocStats(String docId) {
        docRepository.findById(docId)
                .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));
        return chunkRepository.countStatsByDocId(docId);
    }

    /**
     * 查询指定知识库的 chunk/token 汇总统计。
     *
     * @param kbId     知识库 ID
     * @param docCount 已发布文档数（由调用方传入，避免此服务依赖 ingest 服务的分页查询）
     * @return 知识库级 chunk 汇总统计值对象
     */
    public KbChunkStats getKbStats(String kbId, long docCount) {
        KbChunkStats stats = chunkRepository.countStatsByKbId(kbId);
        // 返回含 docCount 的完整统计（docCount 由 AppService 层聚合，不在 Repository 层耦合）
        return new KbChunkStats(kbId, stats.chunkCount(), stats.tokenSum());
    }

    /**
     * 下载文档原始字节流（用于预览）。
     *
     * @param docId 文档 ID
     * @return 文档字节流 + 文档元数据
     */
    public DocPreviewResult getPreview(String docId) {
        KnowledgeDoc doc = docRepository.findById(docId)
                .orElseThrow(() -> new BusinessException(ERROR_DOC_NOT_FOUND, "文档不存在：" + docId));
        byte[] bytes = minioStorageService.download(doc.getStoragePath());
        return new DocPreviewResult(bytes, doc.getFileType(), doc.getFileName());
    }

    /**
     * 批量查询文档 ID → 文件名映射（用于检索结果展示，消除 N+1）。
     *
     * @param docIds 文档 ID 列表
     * @return Map&lt;docId, fileName&gt;
     */
    public java.util.Map<String, String> findFileNamesByIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) return java.util.Map.of();
        return docRepository.findFileNamesByIds(docIds);
    }

    /**
     * 文档预览结果。
     *
     * @param bytes    文件字节内容
     * @param fileType 文件类型（PDF/HTML/DOCX/MARKDOWN）
     * @param fileName 文件名（用于 Content-Disposition）
     */
    public record DocPreviewResult(byte[] bytes, String fileType, String fileName) {}
}
