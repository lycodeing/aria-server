package com.aria.knowledge.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.util.IdGenerator;
import com.aria.common.core.util.TokenUtils;
import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.embedding.EmbeddingService;
import com.aria.knowledge.domain.model.ChunkType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Chunk 精细管理应用服务。
 * 职责：启用/禁用（调整 retrieval_weight）、内容编辑（含重新向量化）、手动添加 Q&A 对。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkAppService {

    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingService         embeddingService;

    /** 禁用 chunk：将 retrieval_weight 设为 0，不物理删除，检索时自动跳过 */
    public void disable(String chunkId) {
        assertChunkExists(chunkId);
        chunkRepository.updateWeight(chunkId, BigDecimal.ZERO);
        log.info("Chunk 已禁用，chunkId={}", chunkId);
    }

    /** 启用 chunk：恢复 retrieval_weight 为 1.0 */
    public void enable(String chunkId) {
        assertChunkExists(chunkId);
        chunkRepository.updateWeight(chunkId, BigDecimal.ONE);
        log.info("Chunk 已启用，chunkId={}", chunkId);
    }

    /**
     * 编辑 chunk 内容并重新向量化。
     * 流程：embed 生成新向量 → 单次 UPDATE 原子写入 content + tokenCount + vector，
     * 避免两步 UPDATE 中途失败导致内容与向量不同步。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateContent(String chunkId, String newContent) {
        if (newContent == null || newContent.isBlank()) {
            throw new BusinessException(400, "Chunk 内容不能为空");
        }
        KnowledgeChunk chunk = assertChunkExists(chunkId);
        chunk.setContent(newContent);
        chunk.setTokenCount(TokenUtils.estimate(newContent));
        // 重新向量化（就地填充 vector 字段）
        embeddingService.embed(List.of(chunk));
        // 单次 UPDATE 原子同步 content、tokenCount、vector
        String vectorStr = chunk.getVector() != null
                ? com.aria.common.core.util.VectorUtils.toStr(chunk.getVector()) : null;
        chunkRepository.updateContentAndVector(chunkId, newContent, chunk.getTokenCount(), vectorStr);
        log.info("Chunk 内容已更新并重新向量化，chunkId={}", chunkId);
    }

    /**
     * 手动添加 Q&A Chunk。
     * Q&A 以「Q：xxx\nA：xxx」格式存入 content，chunkType 标记为 TEXT，向量化后入库。
     *
     * @param docId    归属文档 ID
     * @param kbId     归属知识库 ID
     * @param question 问题
     * @param answer   答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void addQA(String docId, String kbId, String question, String answer) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            throw new BusinessException(400, "问题和答案不能为空");
        }
        String content = "Q：" + question.trim() + "\nA：" + answer.trim();
        KnowledgeChunk chunk = KnowledgeChunk.builder()
            .id(String.valueOf(IdGenerator.nextId()))
            .docId(docId)
            .kbId(kbId)
            .docStatus(DocStatus.PUBLISHED.name())
            .content(content)
            .tokenCount(TokenUtils.estimate(content))
            .retrievalWeight(BigDecimal.ONE)
            .chunkType(ChunkType.TEXT)
            .feedbackDownvotes(0)
            .build();
        // 向量化
        embeddingService.embed(List.of(chunk));
        chunkRepository.save(chunk);
        log.info("Q&A Chunk 已添加，docId={}，chunkId={}", docId, chunk.getId());
    }

    private KnowledgeChunk assertChunkExists(String chunkId) {
        KnowledgeChunk chunk = chunkRepository.findById(chunkId);
        if (chunk == null) {
            throw new BusinessException(4004, "Chunk 不存在：" + chunkId);
        }
        return chunk;
    }
}
