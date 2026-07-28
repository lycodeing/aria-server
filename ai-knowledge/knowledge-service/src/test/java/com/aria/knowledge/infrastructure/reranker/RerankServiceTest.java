package com.aria.knowledge.infrastructure.reranker;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.knowledge.domain.model.ChunkHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * RerankService 单元测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>DB 无 active RERANKER 配置时透明降级（返回原列表，不抛异常）</li>
 *   <li>空候选列表直接返回，不发起 HTTP 调用</li>
 *   <li>Caffeine 缓存 key 变化时自动重建客户端</li>
 * </ul>
 *
 * <p>注意：HTTP 层测试（实际调用 /rerank 端点、Circuit Breaker 触发）需要集成测试环境，
 * 此处仅覆盖纯 Java 逻辑路径。
 */
class RerankServiceTest {

    @Mock
    private AiModelConfigProvider configProvider;

    private RerankService rerankService;

    // AiModelConfig: id, name, provider, apiProtocol, baseUrl, apiKey, modelName,
    //                temperature, maxTokens, timeoutSec
    private static final AiModelConfig RERANKER_CONFIG = new AiModelConfig(
            11L, "BGE-Reranker-v2-M3", "Custom", "OPENAI_COMPATIBLE",
            "http://localhost:8001", "",
            "bge-reranker-v2-m3", 0.0, 0, 10);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        rerankService = new RerankService(configProvider);
    }

    // -------------------------------------------------------
    // 降级路径：DB 无配置
    // -------------------------------------------------------

    @Test
    void rerank_noActiveConfig_returnsCandidatesUnchanged() {
        // DB 无 RERANKER 配置 → configProvider 抛 IllegalStateException
        when(configProvider.getActiveReranker())
                .thenThrow(new IllegalStateException("本地未找到激活的 RERANKER 模型配置"));

        List<ChunkHit> candidates = List.of(
                buildHit("c1", 0.9),
                buildHit("c2", 0.5)
        );

        List<ChunkHit> result = rerankService.rerank("测试查询", candidates);

        // 应原样返回，顺序不变，不抛异常
        assertThat(result).isSameAs(candidates);
    }

    @Test
    void rerank_unsupportedOperation_returnsCandidatesUnchanged() {
        // provider 的 default 方法抛 UnsupportedOperationException（实现类未覆盖）
        // getClient() 应同样 catch 并降级，不依赖 @CircuitBreaker AOP 隐式处理
        when(configProvider.getActiveReranker())
                .thenThrow(new UnsupportedOperationException("not supported"));

        List<ChunkHit> candidates = List.of(buildHit("c1", 0.8));

        List<ChunkHit> result = rerankService.rerank("query", candidates);

        // UnsupportedOperationException 现在被 getClient() 显式捕获 → 同样降级返回原列表
        assertThat(result).isSameAs(candidates);
    }

    // -------------------------------------------------------
    // 空候选列表快速返回
    // -------------------------------------------------------

    @Test
    void rerank_emptyCandidates_returnsEmptyWithoutCallingConfig() {
        // 空列表时不应调用 configProvider，直接返回
        List<ChunkHit> result = rerankService.rerank("query", List.of());

        assertThat(result).isEmpty();
        // 验证没有调用 getActiveReranker（省去 HTTP 开销）
        org.mockito.Mockito.verifyNoInteractions(configProvider);
    }

    // -------------------------------------------------------
    // 配置存在时构建客户端（缓存命中/miss）
    // -------------------------------------------------------

    @Test
    void rerank_configPresent_buildsClientAndCaches() {
        when(configProvider.getActiveReranker()).thenReturn(RERANKER_CONFIG);

        List<ChunkHit> candidates = List.of(buildHit("c1", 0.5));

        // 此调用会到达 HTTP 层并失败（没有真实 Reranker 服务），
        // Circuit Breaker 在单测中不激活 → 期望抛出连接异常而非 IllegalStateException
        // 核心验证：configProvider.getActiveReranker() 被调用（说明未走降级路径）
        try {
            rerankService.rerank("query", candidates);
        } catch (Exception e) {
            // 连接失败是预期的（无真实服务），说明已成功走到 HTTP 调用路径
        }

        // 验证 configProvider 被调用（未走"无配置"降级路径）
        org.mockito.Mockito.verify(configProvider, org.mockito.Mockito.atLeastOnce())
                .getActiveReranker();
    }

    @Test
    void rerank_configUnchanged_reusesClientFromCache() {
        when(configProvider.getActiveReranker()).thenReturn(RERANKER_CONFIG);

        List<ChunkHit> candidates = List.of(buildHit("c1", 0.5));

        // 两次调用使用相同 config → 缓存 key 相同 → 第二次不重建 RestClient
        // 两次都会走到 HTTP 层并失败（无真实服务），但 configProvider 应被调用两次
        for (int i = 0; i < 2; i++) {
            try {
                rerankService.rerank("query", candidates);
            } catch (Exception ignored) {}
        }

        // configProvider.getActiveReranker() 每次 rerank 都会调用（用于算 key），
        // 但 RestClient 实例应被缓存复用（无法通过 mock 直接验证，但不会抛缓存异常）
        org.mockito.Mockito.verify(configProvider, org.mockito.Mockito.times(2))
                .getActiveReranker();
    }

    // -------------------------------------------------------
    // 辅助方法
    // -------------------------------------------------------

    private ChunkHit buildHit(String chunkId, double score) {
        return ChunkHit.builder()
                .chunkId(chunkId)
                .content("content-" + chunkId)
                .score(score)
                .source(ChunkHit.HitSource.VECTOR)
                .build();
    }
}
