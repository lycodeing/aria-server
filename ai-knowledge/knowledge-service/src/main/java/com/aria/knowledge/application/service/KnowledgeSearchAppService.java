package com.aria.knowledge.application.service;

import com.aria.common.core.util.RrfUtils;
import com.aria.knowledge.domain.model.ChunkHit;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.config.SearchProperties;
import com.aria.knowledge.infrastructure.embedding.EmbeddingService;
import com.aria.knowledge.infrastructure.reranker.RerankService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
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
 * 职责：向量召回 + 全文召回（并行执行）→ RRF 融合 → BGE-Reranker 精排 → 返回 topK。
 * 不含业务规则，规则在 domain/service 层。
 *
 * <p>并发策略：双路检索使用专用 IO 线程池（{@code searchExecutor}），
 * 避免占用 ForkJoinPool.commonPool() 公共线程，防止 IO 阻塞饿死其他任务。
 *
 * <p>超时保护：每路检索设置 3s 超时，超时后降级返回空列表，
 * 保证整体 P99 延迟可控，不因 pgvector 慢查询挂起请求线程。
 *
 * <p>召回数量与 topK 解耦：
 * <ul>
 *   <li>每路召回数（{@code recallKVector} / {@code recallKText}）通过 {@link SearchProperties} 独立配置</li>
 *   <li>RRF 融合到 {@code rerankerCandidateLimit}（200）后送 Reranker 精排</li>
 *   <li>精排后再 {@code limit(topK)} 截断，topK 仅控制最终返回数量</li>
 * </ul>
 */
@Slf4j
@Service
public class KnowledgeSearchAppService {

    /** 每路检索超时时间（秒） */
    private static final long SEARCH_TIMEOUT_SECONDS = 3L;

    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingService         embeddingService;
    private final RerankService            rerankService;
    private final SearchProperties         searchProps;
    /** 专用 IO 线程池，避免阻塞 ForkJoinPool.commonPool() */
    private final Executor                 searchExecutor;
    /** 检索质量指标记录器（命中数/top1 分数分布/未命中计数/来源占比） */
    private final MeterRegistry            meterRegistry;

    public KnowledgeSearchAppService(
            KnowledgeChunkRepository chunkRepository,
            EmbeddingService embeddingService,
            RerankService rerankService,
            SearchProperties searchProps,
            @Qualifier("searchExecutor") Executor searchExecutor,
            MeterRegistry meterRegistry) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.rerankService    = rerankService;
        this.searchProps      = searchProps;
        this.searchExecutor   = searchExecutor;
        this.meterRegistry    = meterRegistry;
    }

    /**
     * 混合检索入口：BM25 + 向量双路并行召回 → RRF 融合 → Reranker 精排 → topK。
     *
     * @param query 用户查询文本
     * @param kbId  目标知识库 ID
     * @param topK  最终返回条数
     * @return 精排后按相关性降序排列的 chunk 列表
     */
    public List<ChunkHit> hybridSearch(String query, String kbId, int topK) {
        float[] queryVector = embeddingService.encode(query);

        // 召回数量独立于 topK，通过 SearchProperties 配置
        int recallVector = searchProps.recallKVector();
        int recallText   = searchProps.recallKText();

        // 使用专用 IO 线程池，避免 ForkJoinPool.commonPool() 被 DB 阻塞操作占满
        CompletableFuture<List<ChunkHit>> vectorFuture = CompletableFuture.supplyAsync(
            () -> chunkRepository.vectorSearch(queryVector, recallVector, kbId), searchExecutor);
        CompletableFuture<List<ChunkHit>> textFuture = CompletableFuture.supplyAsync(
            () -> chunkRepository.fullTextSearch(query, recallText, kbId), searchExecutor);

        // safeGet：超时 3s 降级返回空列表，保证整体延迟可控
        List<ChunkHit> vectorHits = safeGet(vectorFuture, "向量检索", kbId);
        List<ChunkHit> textHits   = safeGet(textFuture,   "全文检索", kbId);

        log.info("[hybridSearch] kbId={} query_len={} vector_hits={} text_hits={}",
            kbId, query.length(), vectorHits.size(), textHits.size());

        // 两路均无结果时直接返回，不进行无意义的 RRF 计算
        if (vectorHits.isEmpty() && textHits.isEmpty()) {
            recordSearchMetrics(List.of());
            return List.of();
        }

        // RRF 融合：先到 rerankerCandidateLimit，不直接截断到 topK
        List<String> vectorIds = vectorHits.stream().map(ChunkHit::getChunkId).toList();
        List<String> textIds   = textHits.stream().map(ChunkHit::getChunkId).toList();
        List<String> fusedIds  = RrfUtils.fuseWithK(
            searchProps.rerankerCandidateLimit(),
            searchProps.rrfK(),
            List.of(vectorIds, textIds));

        // 向量结果优先覆盖同 chunkId 的全文结果（HitSource 标记更精确）
        Map<String, ChunkHit> chunkMap = vectorHits.stream()
            .collect(Collectors.toMap(ChunkHit::getChunkId, h -> h));
        textHits.forEach(h -> chunkMap.putIfAbsent(h.getChunkId(), h));

        List<ChunkHit> candidates = fusedIds.stream()
            .filter(chunkMap::containsKey)
            .map(chunkMap::get)
            .collect(Collectors.toList());

        log.debug("[hybridSearch] kbId={} fused={}", kbId, candidates.size());

        // Reranker 精排后截断到 topK
        List<ChunkHit> reranked = rerankService.rerank(query, candidates);

        log.info("[hybridSearch] kbId={} after_rerank={} topK={}", kbId, reranked.size(), topK);

        List<ChunkHit> finalHits = reranked.stream().limit(topK).collect(Collectors.toList());
        recordSearchMetrics(finalHits);
        return finalHits;
    }

    /**
     * 记录 RAG 检索质量指标（Micrometer，供 /actuator/metrics 与 Prometheus 抓取）。
     *
     * <ul>
     *   <li>{@code rag.search.hit_count}（DistributionSummary）：每次检索命中 chunk 数分布</li>
     *   <li>{@code rag.search.top1_score}（DistributionSummary）：top-1 chunk 分数分布</li>
     *   <li>{@code rag.search.miss_total}（Counter）：完全未命中（0 结果）次数</li>
     *   <li>{@code rag.search.source_total}（Counter，tag=source）：各来源命中占比</li>
     * </ul>
     *
     * <p>指标记录失败不影响检索主流程。
     */
    private void recordSearchMetrics(List<ChunkHit> hits) {
        try {
            int hitCount = hits.size();
            meterRegistry.summary("rag.search.hit_count").record(hitCount);
            if (hitCount == 0) {
                meterRegistry.counter("rag.search.miss_total").increment();
                return;
            }
            ChunkHit top1 = hits.get(0);
            meterRegistry.summary("rag.search.top1_score").record(top1.getScore());
            String source = top1.getSource() != null ? top1.getSource().name() : "UNKNOWN";
            meterRegistry.counter("rag.search.source_total", "source", source).increment();
        } catch (Exception e) {
            log.debug("[Search] Micrometer 指标记录失败（非关键）", e);
        }
    }

    /**
     * 管理后台检索测试入口（不限 AK/SK，返回 source 字段用于前端展示命中来源）。
     */
    public List<ChunkHit> managementSearch(String query, String kbId, int topK) {
        return hybridSearch(query, kbId, topK);
    }

    /**
     * 独立精排入口，供 {@code POST /internal/knowledge/rerank} 调用。
     *
     * <p>调用方自行提供候选列表，本方法只负责 Reranker 打分排序，不做召回。
     * Reranker 不可用时透明降级返回原候选顺序。
     *
     * @param query      用户查询文本
     * @param candidates 外部提供的候选 chunk 列表
     * @return 按 Reranker 分数降序排列的列表（降级时返回原顺序）
     */
    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates) {
        return rerankService.rerank(query, candidates);
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
