package com.aria.knowledge.infrastructure.reranker;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.knowledge.domain.model.ChunkHit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * BGE-Reranker 精排服务（兼容 infinity-emb / Xinference 等 OpenAI-compatible /rerank 端点）。
 *
 * <p>配置来源改为 {@link AiModelConfigProvider#getActiveReranker()}，支持：
 * <ul>
 *   <li>热切换：在管理后台修改 RERANKER 配置后，下次请求自动使用新配置，无需重启</li>
 *   <li>降级：DB 无 active RERANKER 配置时，{@link #rerank} 透明返回原候选列表</li>
 * </ul>
 *
 * <p>Caffeine 缓存按 {@code SHA-256(baseUrl|modelName|maskedApiKey)} 做 key，
 * 命中时复用 RestClient 实例；配置变更时 key 不同 → 缓存 miss → 自动重建。
 *
 * <p>Circuit Breaker（Reranker 服务宕机）与配置缺失（DB 无记录）是两个独立的降级路径：
 * <ul>
 *   <li>配置缺失 → {@code getClient()} 返回 empty → 直接返回 candidates，不调远端</li>
 *   <li>服务宕机 → CircuitBreaker fallback → 返回 candidates，不抛异常</li>
 * </ul>
 *
 * <p>精排 API 格式（OpenAI 兼容 /rerank 端点）：
 * <pre>
 * POST {baseUrl}/rerank
 * { "model": "bge-reranker-v2-m3", "query": "...", "documents": ["...", "..."], "top_n": N }
 *
 * 响应：
 * { "results": [ {"index": 0, "relevance_score": 0.95}, ... ] }
 * </pre>
 *
 * @author lycodeing
 * @since 2026-07
 */
@Slf4j
@Service
public class RerankService {

    /**
     * Caffeine 缓存：max 3 个配置版本，30 分钟未访问自动淘汰
     */
    private final Cache<String, RerankerClient> clientCache = Caffeine.newBuilder()
            .maximumSize(3)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();
    private final AiModelConfigProvider configProvider;

    public RerankService(AiModelConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    /**
     * 对候选 chunk 列表执行 cross-encoder 精排。
     *
     * <p>若 DB 无 active RERANKER 配置，透明降级返回原候选列表（不抛异常，不打 ERROR 日志）。
     *
     * @param query      用户查询文本
     * @param candidates 混合检索召回的候选列表
     * @return 按 Reranker 分数降序排列的列表，score 和 source 已更新；
     * 降级时返回原列表顺序（RRF 融合顺序）
     */
    @CircuitBreaker(name = "reranker", fallbackMethod = "rerankFallback")
    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        Optional<RerankerClient> clientOpt = getClient();
        if (clientOpt.isEmpty()) {
            log.debug("[Reranker] 无 active RERANKER 配置，跳过精排，候选数={}", candidates.size());
            return candidates;
        }

        RerankerClient client = clientOpt.get();
        List<String> documents = candidates.stream().map(ChunkHit::getContent).toList();
        Map<String, Object> requestBody = Map.of(
                "model", client.modelName(),
                "query", query,
                "documents", documents,
                "top_n", documents.size()   // 显式传 top_n 防止服务端默认截断
        );

        RerankResponse response = client.restClient().post()
                .uri("/rerank")
                .body(requestBody)
                .retrieve()
                .body(RerankResponse.class);

        if (response == null || response.results() == null) {
            log.warn("[Reranker] 响应为空，返回原始候选列表");
            return candidates;
        }

        log.debug("[Reranker] 精排完成，候选数={}，query_len={}", candidates.size(), query.length());

        // 按 Reranker 分数回填并排序
        List<ChunkHit> scored = new ArrayList<>(candidates.size());
        for (RerankResult result : response.results()) {
            if (result.index() >= 0 && result.index() < candidates.size()) {
                scored.add(candidates.get(result.index())
                        .withScore(result.relevanceScore())
                        .withSource(ChunkHit.HitSource.RERANK));
            }
        }
        scored.sort(Comparator.comparingDouble(ChunkHit::getScore).reversed());
        return scored;
    }

    // -------------------------------------------------------
    // 公开接口
    // -------------------------------------------------------

    /**
     * 获取（或重建）当前 Reranker 客户端。
     *
     * <p>每次调用都从 {@code configProvider} 拿最新配置，算 SHA-256 key，
     * 命中缓存则复用，miss 则构造新 RestClient 并写入缓存。
     * 配置变更时 key 变化 → 自动重建，旧实例 30 分钟后淘汰。
     *
     * @return Optional.empty() 表示 DB 无 active RERANKER 配置，调用方应降级
     */
    private Optional<RerankerClient> getClient() {
        AiModelConfig config;
        try {
            config = configProvider.getActiveReranker();
        } catch (IllegalStateException | UnsupportedOperationException e) {
            // IllegalStateException    — DB 无 active RERANKER 配置（正常状态，未配置 = 不启用）
            // UnsupportedOperationException — AiModelConfigProvider 实现类未覆盖 getActiveReranker()
            // 两种情况均降级跳过精排，不依赖 @CircuitBreaker AOP 来隐式处理
            log.debug("[Reranker] 无/不支持 RERANKER 配置，降级跳过精排: {}", e.getMessage());
            return Optional.empty();
        }

        String cacheKey = buildCacheKey(config);
        RerankerClient cached = clientCache.getIfPresent(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        // 缓存 miss：构造新 RestClient
        RerankerClient newClient = buildClient(config);
        clientCache.put(cacheKey, newClient);
        log.info("[Reranker] 构造新 RerankerClient，model={}, baseUrl={}",
                config.modelName(), config.baseUrl());
        return Optional.of(newClient);
    }

    // -------------------------------------------------------
    // 内部实现
    // -------------------------------------------------------

    /**
     * 构造带超时的 RestClient。
     */
    private RerankerClient buildClient(AiModelConfig config) {
        int timeoutSec = config.timeoutSec() > 0 ? config.timeoutSec() : 10;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSec))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSec));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(factory);

        // API Key 非空时加入 Authorization 头（本地 Ollama 等无鉴权场景留空即可）
        String apiKey = config.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }

        return new RerankerClient(builder.build(), config.modelName());
    }

    /**
     * SHA-256(baseUrl | modelName | maskedApiKey)。
     * apiKey 仅取前/后 4 位脱敏参与 hash，避免明文出现在缓存 key 中。
     */
    private String buildCacheKey(AiModelConfig config) {
        String maskedKey = maskApiKey(config.apiKey());
        String raw = config.baseUrl() + "|" + config.modelName() + "|" + maskedKey;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 强制要求实现的算法，不会发生
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * Reranker 服务熔断降级：返回原始候选列表，不影响检索可用性。
     */
    @SuppressWarnings("unused")
    private List<ChunkHit> rerankFallback(String query, List<ChunkHit> candidates, Throwable t) {
        log.warn("[Reranker] 精排服务不可用（Circuit Breaker），使用 RRF 结果，原因: {}", t.getMessage());
        return candidates;
    }

    /**
     * RestClient + modelName 的缓存封装，按配置 hash 区分不同实例。
     * record 不可变，线程安全。
     */
    private record RerankerClient(RestClient restClient, String modelName) {
    }

    // ---- API 响应 DTO（内部使用 record） ----

    private record RerankResponse(List<RerankResult> results) {
    }

    /**
     * relevance_score 是 snake_case，通过 @JsonProperty 映射
     */
    private record RerankResult(
            int index,
            @com.fasterxml.jackson.annotation.JsonProperty("relevance_score") double relevanceScore
    ) {
    }
}
