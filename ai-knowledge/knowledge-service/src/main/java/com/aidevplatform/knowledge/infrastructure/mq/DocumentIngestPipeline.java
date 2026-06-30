package com.aidevplatform.knowledge.infrastructure.mq;

import com.aidevplatform.common.core.util.IdGenerator;
import com.aidevplatform.common.core.util.TokenUtils;
import com.aidevplatform.knowledge.domain.model.DocStatus;
import com.aidevplatform.knowledge.domain.model.KnowledgeChunk;
import com.aidevplatform.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aidevplatform.knowledge.domain.repository.KnowledgeDocRepository;
import com.aidevplatform.knowledge.domain.service.ChunkQualityDomainService;
import com.aidevplatform.knowledge.infrastructure.embedding.EmbeddingService;
import com.aidevplatform.knowledge.infrastructure.parser.MultiFormatParser;
import com.aidevplatform.knowledge.infrastructure.splitter.RecursiveChunkSplitter;
import com.aidevplatform.knowledge.infrastructure.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 文档摄取管道（异步处理，由 DocIngestConsumer 触发）。
 * 职责：解析 → 拆分 → 质量过滤 → Embedding → 写向量库 → 更新文档状态。
 *
 * <p>完整流程：
 * <ol>
 *   <li>从存储服务下载文件字节流</li>
 *   <li>MultiFormatParser 解析为纯文本</li>
 *   <li>RecursiveChunkSplitter 拆分 chunk</li>
 *   <li>ChunkQualityDomainService 过滤低质量 chunk</li>
 *   <li>注入面包屑、kbId 等元数据</li>
 *   <li>EmbeddingService 批量向量化（填充 vector 字段）</li>
 *   <li>KnowledgeChunkRepository 写入 pgvector</li>
 *   <li>更新文档状态为 PUBLISHED</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestPipeline {

    private final MultiFormatParser        parser;
    private final RecursiveChunkSplitter   splitter;
    private final ChunkQualityDomainService qualityService;
    private final EmbeddingService         embeddingService;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeDocRepository   docRepository;
    private final MinioStorageService      minioStorageService;

    /**
     * 执行完整摄取管道，事务保证摄取成功或全部回滚。
     *
     * <p>幂等性保障（阿里规约：消息处理必须幂等）：
     * <ol>
     *   <li>入口判断 DB 文档状态：已 PUBLISHED 或 FAILED 直接返回，避免重复执行</li>
     *   <li>写 chunk 前先 deleteByDocId，避免 MQ 重试导致脏数据残留</li>
     * </ol>
     *
     * @param event 文档摄取事件（来自 RabbitMQ knowledge.doc.ingest.queue）
     */
    @Transactional(rollbackFor = Exception.class)
    public void process(DocIngestEvent event) {
        log.info("开始摄取文档，docId={}，fileType={}", event.getDocId(), event.getFileType());

        // Step 0：幂等校验 - 已是 PUBLISHED 或 FAILED 状态不再处理
        var docOpt = docRepository.findById(event.getDocId());
        if (docOpt.isEmpty()) {
            log.warn("文档不存在，可能 DB 写入尚未提交，跳过摄取等待重试 docId={}", event.getDocId());
            throw new IllegalStateException("文档记录不存在: " + event.getDocId());
        }
        DocStatus currentStatus = docOpt.get().getStatus();
        if (currentStatus == DocStatus.PUBLISHED || currentStatus == DocStatus.FAILED
                || currentStatus == DocStatus.DEPRECATED) {
            log.info("文档已为终态 status={}，跳过重复摄取 docId={}", currentStatus, event.getDocId());
            return;
        }

        // Step 1：下载文件内容
        byte[] content = loadContent(event.getStoragePath());

        // Step 2：解析为纯文本
        String rawText = parser.parse(content, event.getFileType());
        log.debug("文档解析完成，docId={}，字符数={}", event.getDocId(), rawText.length());

        // Step 3：拆分 chunk
        List<String> rawChunks = splitter.split(rawText, event.getFileType());

        // Step 4：质量过滤
        List<String> qualified = qualityService.filter(rawChunks);
        log.info("Chunk 拆分完成，docId={}，原始={}，合格={}", 
            event.getDocId(), rawChunks.size(), qualified.size());

        if (qualified.isEmpty()) {
            log.warn("文档所有 chunk 均未通过质量过滤，docId={}，跳过摄取", event.getDocId());
            docRepository.updateStatusBatch(List.of(event.getDocId()), DocStatus.PUBLISHED);
            return;
        }

        // Step 5：构建 KnowledgeChunk 领域对象（注入元数据）
        List<KnowledgeChunk> chunks = buildChunks(qualified, event);

        // Step 6：批量 Embedding（BGE-M3，就地填充 vector 字段）
        embeddingService.embed(chunks);

        // Step 7：写入 pgvector
        // 幂等保障：MQ 重试场景下先删旧 chunk，再写新 chunk，避免重复
        chunkRepository.deleteByDocId(event.getDocId());
        chunkRepository.saveAll(chunks);

        // Step 8：更新文档状态为 PUBLISHED
        docRepository.updateStatusBatch(List.of(event.getDocId()), DocStatus.PUBLISHED);
        log.info("文档摄取完成，docId={}，写入 chunk={}", event.getDocId(), chunks.size());
    }

    /**
     * 构建 chunk 领域对象，注入 kbId / docStatus / tokenCount 等必要元数据。
     * kbId 冗余字段在此处赋值，确保后续检索无需 JOIN knowledge_doc。
     */
    private List<KnowledgeChunk> buildChunks(List<String> texts, DocIngestEvent event) {
        List<KnowledgeChunk> result = new java.util.ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            result.add(KnowledgeChunk.builder()
                .id(String.valueOf(IdGenerator.nextId()))
                .docId(event.getDocId())
                .kbId(event.getKbId())
                .docStatus(DocStatus.PUBLISHED.name())
                .content(texts.get(i))
                .tokenCount(TokenUtils.estimate(texts.get(i)))
                .retrievalWeight(BigDecimal.ONE)
                .feedbackDownvotes(0)
                .build());
        }
        return result;
    }

    /**
     * 从 MinIO 下载文件内容。
     * storagePath 格式：{@code oss://{bucket}/docs/{docId}/{filename}}
     *
     * @param storagePath MinIO 存储路径
     * @return 文件字节内容
     */
    protected byte[] loadContent(String storagePath) {
        log.debug("[Pipeline] 从 MinIO 下载文件: {}", storagePath);
        return minioStorageService.download(storagePath);
    }
}
