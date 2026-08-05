package com.aria.knowledge.application.service;

import com.aria.knowledge.domain.model.ChunkHit;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.config.SearchProperties;
import com.aria.knowledge.infrastructure.embedding.EmbeddingService;
import com.aria.knowledge.infrastructure.reranker.RerankService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * KnowledgeSearchAppService 单元测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>召回数量使用 SearchProperties（recallKVector / recallKText），不再绑定 topK*2</li>
 *   <li>RRF 融合使用配置的 rerankerCandidateLimit 和 rrfK，而非直接截断到 topK</li>
 *   <li>Reranker 精排后再 limit(topK)</li>
 *   <li>双路均空时直接返回空列表</li>
 *   <li>单路为空时使用另一路结果</li>
 *   <li>Reranker 返回多于 topK 时正确截断</li>
 * </ul>
 */
class KnowledgeSearchAppService_SearchPropsTest {

    @Mock private KnowledgeChunkRepository chunkRepository;
    @Mock private EmbeddingService         embeddingService;
    @Mock private RerankService            rerankService;

    /** 同步 Executor，让 CompletableFuture 在测试线程中串行执行，避免并发复杂性 */
    private final Executor syncExecutor = Runnable::run;

    /**
     * SearchProperties record — 直接构造，使用与生产一致的推荐参数。
     * recallKVector=80, recallKText=100, rerankerCandidateLimit=200, rrfK=40
     */
    private final SearchProperties props = new SearchProperties("simple", 80, 100, 200, 40);

    private KnowledgeSearchAppService service;

    private static final float[] DUMMY_VECTOR = new float[]{0.1f, 0.2f, 0.3f};
    private static final String  KB_ID        = "kb-001";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(embeddingService.encode(anyString())).thenReturn(DUMMY_VECTOR);
        service = new KnowledgeSearchAppService(
                chunkRepository, embeddingService, rerankService, props, syncExecutor,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    // -------------------------------------------------------
    // 核心：召回数量使用 SearchProperties，不绑定 topK
    // -------------------------------------------------------

    @Test
    void hybridSearch_usesRecallKVectorNotTopKMultiplied() {
        int topK = 5;
        when(chunkRepository.vectorSearch(any(), anyInt(), anyString()))
                .thenReturn(hits("v", 10));
        when(chunkRepository.fullTextSearch(anyString(), anyInt(), anyString()))
                .thenReturn(hits("t", 5));
        when(rerankService.rerank(anyString(), anyList()))
                .thenAnswer(inv -> inv.getArgument(1));

        service.hybridSearch("query", KB_ID, topK);

        // 向量召回应使用 recallKVector=80，而非 topK*2=10
        verify(chunkRepository).vectorSearch(eq(DUMMY_VECTOR), eq(80), eq(KB_ID));
    }

    @Test
    void hybridSearch_usesRecallKTextNotTopKMultiplied() {
        int topK = 5;
        when(chunkRepository.vectorSearch(any(), anyInt(), anyString()))
                .thenReturn(hits("v", 10));
        when(chunkRepository.fullTextSearch(anyString(), anyInt(), anyString()))
                .thenReturn(hits("t", 10));
        when(rerankService.rerank(anyString(), anyList()))
                .thenAnswer(inv -> inv.getArgument(1));

        service.hybridSearch("query", KB_ID, topK);

        // 全文召回应使用 recallKText=100，而非 topK*2=10
        verify(chunkRepository).fullTextSearch(eq("query"), eq(100), eq(KB_ID));
    }

    // -------------------------------------------------------
    // RRF 融合候选数 <= rerankerCandidateLimit，不直接截断到 topK
    // -------------------------------------------------------

    @Test
    void hybridSearch_rerankCalledWithCandidatesNotTopK() {
        int topK = 5;
        // 向量召回 20 条，全文召回 20 条，合并去重后 40 条
        when(chunkRepository.vectorSearch(any(), anyInt(), anyString()))
                .thenReturn(hits("v", 20));
        when(chunkRepository.fullTextSearch(anyString(), anyInt(), anyString()))
                .thenReturn(hits("t", 20));
        // 让 reranker 原样返回候选
        when(rerankService.rerank(anyString(), anyList()))
                .thenAnswer(inv -> inv.getArgument(1));

        service.hybridSearch("query", KB_ID, topK);

        // Reranker 接收的候选数应 > topK（最多 40 条，即 rerankerCandidateLimit=200 内）
        verify(rerankService).rerank(eq("query"), argThat(list -> list.size() > topK));
    }

    // -------------------------------------------------------
    // 精排后截断到 topK
    // -------------------------------------------------------

    @Test
    void hybridSearch_resultLimitedToTopK() {
        int topK = 3;
        when(chunkRepository.vectorSearch(any(), anyInt(), anyString()))
                .thenReturn(hits("v", 15));
        when(chunkRepository.fullTextSearch(anyString(), anyInt(), anyString()))
                .thenReturn(hits("t", 15));
        // Reranker 返回所有候选（不截断）
        when(rerankService.rerank(anyString(), anyList()))
                .thenAnswer(inv -> inv.getArgument(1));

        List<ChunkHit> result = service.hybridSearch("query", KB_ID, topK);

        assertThat(result).hasSize(topK);
    }

    // -------------------------------------------------------
    // 双路均空
    // -------------------------------------------------------

    @Test
    void hybridSearch_bothPathsEmpty_returnsEmpty() {
        when(chunkRepository.vectorSearch(any(), anyInt(), anyString()))
                .thenReturn(List.of());
        when(chunkRepository.fullTextSearch(anyString(), anyInt(), anyString()))
                .thenReturn(List.of());

        List<ChunkHit> result = service.hybridSearch("query", KB_ID, 5);

        assertThat(result).isEmpty();
        // 两路均空时不应调用 Reranker
        verifyNoInteractions(rerankService);
    }

    // -------------------------------------------------------
    // 单路为空（模拟超时降级）
    // -------------------------------------------------------

    @Test
    void hybridSearch_vectorPathEmpty_returnsBm25Results() {
        int topK = 3;
        when(chunkRepository.vectorSearch(any(), anyInt(), anyString()))
                .thenReturn(List.of());   // 向量路降级
        when(chunkRepository.fullTextSearch(anyString(), anyInt(), anyString()))
                .thenReturn(hits("t", 10));
        when(rerankService.rerank(anyString(), anyList()))
                .thenAnswer(inv -> inv.getArgument(1));

        List<ChunkHit> result = service.hybridSearch("query", KB_ID, topK);

        assertThat(result).hasSize(topK);
        // 所有结果来自全文检索
        assertThat(result).allMatch(h -> h.getChunkId().startsWith("t-"));
    }

    @Test
    void hybridSearch_textPathEmpty_returnsVectorResults() {
        int topK = 3;
        when(chunkRepository.vectorSearch(any(), anyInt(), anyString()))
                .thenReturn(hits("v", 10));
        when(chunkRepository.fullTextSearch(anyString(), anyInt(), anyString()))
                .thenReturn(List.of());   // 全文路降级
        when(rerankService.rerank(anyString(), anyList()))
                .thenAnswer(inv -> inv.getArgument(1));

        List<ChunkHit> result = service.hybridSearch("query", KB_ID, topK);

        assertThat(result).hasSize(topK);
        assertThat(result).allMatch(h -> h.getChunkId().startsWith("v-"));
    }

    // -------------------------------------------------------
    // 独立精排入口
    // -------------------------------------------------------

    @Test
    void rerank_delegatesToRerankService() {
        List<ChunkHit> candidates = hits("c", 5);
        List<ChunkHit> expected   = hits("c", 3);
        when(rerankService.rerank("q", candidates)).thenReturn(expected);

        List<ChunkHit> result = service.rerank("q", candidates);

        assertThat(result).isSameAs(expected);
    }

    // -------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------

    /** 生成 n 个 ChunkHit，chunkId 格式为 "{prefix}-0" .. "{prefix}-(n-1)" */
    private List<ChunkHit> hits(String prefix, int n) {
        List<ChunkHit> list = new ArrayList<>();
        IntStream.range(0, n).forEach(i ->
            list.add(ChunkHit.builder()
                    .chunkId(prefix + "-" + i)
                    .content("content-" + prefix + "-" + i)
                    .score(1.0 - i * 0.05)
                    .source(ChunkHit.HitSource.VECTOR)
                    .kbId(KB_ID)
                    .build())
        );
        return list;
    }
}
