package com.aidevplatform.knowledge.application.service;

import com.aidevplatform.common.core.util.RrfUtils;
import com.aidevplatform.knowledge.domain.model.ChunkHit;
import com.aidevplatform.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aidevplatform.knowledge.infrastructure.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 知识库检索应用服务（混合检索用例编排）。
 * 职责：向量召回 + 全文召回（并行执行）→ RRF 融合排序 → 返回 topK。
 * 不含业务规则，规则在 domain/service 层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchAppService {

    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingService         embeddingService;

    /**
     * 混合检索入口：BM25 + 向量双路并行召回，RRF 融合后返回 topK 条。
     *
     * @param query 用户查询文本（已经过改写）
     * @param kbId  目标知识库 ID
     * @param topK  最终返回条数
     * @return RRF 融合后按相关性降序排列的 chunk 列表
     */
    public List<ChunkHit> hybridSearch(String query, String kbId, int topK) {
        float[] queryVector = embeddingService.encode(query);

        // 并行执行双路检索，互不阻塞
        CompletableFuture<List<ChunkHit>> vectorFuture = CompletableFuture.supplyAsync(
            () -> chunkRepository.vectorSearch(queryVector, topK * 2, kbId));
        CompletableFuture<List<ChunkHit>> textFuture = CompletableFuture.supplyAsync(
            () -> chunkRepository.fullTextSearch(query, topK * 2, kbId));

        List<ChunkHit> vectorHits = vectorFuture.join();
        List<ChunkHit> textHits   = textFuture.join();
        log.debug("混合检索完成，kbId={}，向量召回={}，全文召回={}", 
            kbId, vectorHits.size(), textHits.size());

        // RRF 融合：只传 chunkId 列表给工具类，再按融合顺序回填 ChunkHit
        List<String> vectorIds = vectorHits.stream().map(ChunkHit::getChunkId).toList();
        List<String> textIds   = textHits.stream().map(ChunkHit::getChunkId).toList();
        List<String> fusedIds  = RrfUtils.fuse(topK, List.of(vectorIds, textIds));

        // 合并两路 chunk map，向量结果优先（HitSource 标记）
        Map<String, ChunkHit> chunkMap = textHits.stream()
            .collect(Collectors.toMap(ChunkHit::getChunkId, h -> h, (a, b) -> a));
        vectorHits.forEach(h -> chunkMap.put(h.getChunkId(), h));

        return fusedIds.stream()
            .filter(chunkMap::containsKey)
            .map(chunkMap::get)
            .toList();
    }

    /**
     * BGE-Reranker 精排（当前返回原始列表，实现阶段对接 Reranker API）。
     *
     * @param query      用户查询文本
     * @param candidates 混合检索召回的候选列表
     * @return 精排后的列表
     */
    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates) {
        // TODO：实现阶段对接 BGE-Reranker API（cross-encoder 精排）
        log.debug("Reranker 尚未接入，直接返回原始候选列表，候选数={}", candidates.size());
        return candidates;
    }
}
