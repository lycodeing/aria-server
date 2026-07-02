package com.aria.knowledge.application.service;

import com.aria.common.core.util.RrfUtils;
import com.aria.knowledge.domain.model.ChunkHit;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.embedding.EmbeddingService;
import com.aria.knowledge.infrastructure.reranker.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 知识库检索应用服务（混合检索用例编排）。
 * 职责：向量召回 + 全文召回（并行执行）→ RRF 融合排序 → 返回 topK。
 * 不含业务规则，规则在 domain/service 层。
 *
 * <p>并发策略：双路检索使用专用 IO 线程池（{@code searchExecutor}），
 * 避免占用 ForkJoinPool.commonPool() 公共线程，防止 IO 阻塞饿死其他任务。
 *
 * <p>超时保护：每路检索设置 3s 超时，超时后降级返回空列表，
 * 保证整体 P99 延迟可控，不因 pgvector 慢查询挂起请求线程。
 */
@Slf4j
@Service
public class KnowledgeSearchAppService {

    /** 每路检索超时时间（秒） */
    private static final long SEARCH_TIMEOUT_SECONDS = 3L;

    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingService         embeddingService;
    /** 专用 IO 线程池，避免阻塞 ForkJoinPool.commonPool() */
    private final Executor                 searchExecutor;

    /** Reranker 服务可选注入，仅在 knowledge.reranker.enabled=true 时存在 */
    @Autowired(required = false)
    private RerankService rerankService;

    public KnowledgeSearchAppService(
            KnowledgeChunkRepository chunkRepository,
            EmbeddingService embeddingService,
            @Qualifier("searchExecutor") Executor searchExecutor) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.searchExecutor   = searchExecutor;
    }

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

        // 使用专用 IO 线程池，避免 ForkJoinPool.commonPool() 被 DB 阻塞操作占满
        CompletableFuture<List<ChunkHit>> vectorFuture = CompletableFuture.supplyAsync(
            () -> chunkRepository.vectorSearch(queryVector, topK * 2, kbId), searchExecutor);
        CompletableFuture<List<ChunkHit>> textFuture = CompletableFuture.supplyAsync(
            () -> chunkRepository.fullTextSearch(query, topK * 2, kbId), searchExecutor);

        // orTimeout + 超时降级：慢查询时返回空列表而非挂起请求线程
        List<ChunkHit> vectorHits = safeGet(vectorFuture, "向量检索", kbId);
        List<ChunkHit> textHits   = safeGet(textFuture,   "全文检索", kbId);

        log.debug("混合检索完成，kbId={}，向量召回={}，全文召回={}", 
            kbId, vectorHits.size(), textHits.size());

        // 两路均无结果时直接返回，不进行无意义的 RRF 计算
        if (vectorHits.isEmpty() && textHits.isEmpty()) {
            return List.of();
        }

        // RRF 融合：只传 chunkId 列表给工具类，再按融合顺序回填 ChunkHit
        List<String> vectorIds = vectorHits.stream().map(ChunkHit::getChunkId).toList();
        List<String> textIds   = textHits.stream().map(ChunkHit::getChunkId).toList();
        List<String> fusedIds  = RrfUtils.fuse(topK, List.of(vectorIds, textIds));

        // 向量结果优先覆盖同 chunkId 的全文结果（HitSource 标记更精确）
        Map<String, ChunkHit> chunkMap = vectorHits.stream()
            .collect(Collectors.toMap(ChunkHit::getChunkId, h -> h));
        // 全文结果补充向量未覆盖的 chunk
        textHits.forEach(h -> chunkMap.putIfAbsent(h.getChunkId(), h));

        return fusedIds.stream()
            .filter(chunkMap::containsKey)
            .map(chunkMap::get)
            .toList();
    }

    /**
     * BGE-Reranker 精排。
     *
     * <p>若 {@code knowledge.reranker.enabled=true} 且 Reranker 服务正常，
     * 则通过 cross-encoder 重新打分排序；否则原样返回 RRF 融合结果。
     *
     * @param query      用户查询文本
     * @param candidates 混合检索召回的候选列表
     * @return 精排后的列表（Reranker 不可用时返回原始列表）
     */
    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates) {
        if (rerankService == null) {
            log.debug("[Search] Reranker 未启用，返回 RRF 融合结果，候选数={}", candidates.size());
            return candidates;
        }
        return rerankService.rerank(query, candidates);
    }

    /**
     * 管理后台检索测试入口（不限 AK/SK，返回 source 字段用于前端展示命中来源）。
     */
    public List<ChunkHit> managementSearch(String query, String kbId, int topK) {
        return hybridSearch(query, kbId, topK);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /**
     * 安全获取 CompletableFuture 结果，超时时降级返回空列表。
     * 保证整体 hybridSearch 延迟可控，不因单路慢查询挂起请求线程。
     */
    private List<ChunkHit> safeGet(CompletableFuture<List<ChunkHit>> future,
                                    String label, String kbId) {
        try {
            return future.orTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                log.warn("[Search] {} 超时（{}s），降级返回空列表 kbId={}", label, SEARCH_TIMEOUT_SECONDS, kbId);
            } else {
                log.error("[Search] {} 异常，降级返回空列表 kbId={}", label, kbId, cause);
            }
            return List.of();
        }
    }
}
