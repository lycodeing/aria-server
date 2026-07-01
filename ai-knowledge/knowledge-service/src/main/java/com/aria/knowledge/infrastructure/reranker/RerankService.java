package com.aria.knowledge.infrastructure.reranker;

import com.aria.knowledge.domain.model.ChunkHit;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/**
 * BGE-Reranker 精排服务（直接 HTTP 调用，兼容 infinity-emb / Xinference 等部署方式）。
 *
 * <p>仅在 {@code knowledge.reranker.enabled=true} 时启用；默认关闭不影响现有功能。
 *
 * <p>精排 API 格式（OpenAI 兼容 /rerank 端点）：
 * <pre>
 * POST {baseUrl}/rerank
 * { "model": "bge-reranker-v2-m3", "query": "...", "documents": ["...", "..."] }
 *
 * 响应：
 * { "results": [ {"index": 0, "relevance_score": 0.95}, ... ] }
 * </pre>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "knowledge.reranker.enabled", havingValue = "true")
public class RerankService {

    private final RestClient restClient;

    @Value("${knowledge.reranker.model-name:bge-reranker-v2-m3}")
    private String modelName;

    public RerankService(
            @Value("${knowledge.reranker.base-url:http://localhost:8001}") String baseUrl,
            @Value("${knowledge.reranker.timeout-seconds:10}") int timeoutSeconds) {
        // 使用 JDK HttpClient 设置连接和读取超时，避免慢服务拖死线程
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * 对候选 chunk 列表执行 cross-encoder 精排。
     *
     * @param query      用户查询文本
     * @param candidates 混合检索召回的候选列表
     * @return 按 Reranker 分数降序排列的列表，score 和 source 已更新
     */
    @CircuitBreaker(name = "reranker", fallbackMethod = "rerankFallback")
    public List<ChunkHit> rerank(String query, List<ChunkHit> candidates) {
        if (candidates.isEmpty()) return candidates;

        // 构造请求体（显式传 top_n 防止服务端默认截断）
        List<String> documents = candidates.stream()
                .map(ChunkHit::getContent)
                .toList();
        Map<String, Object> requestBody = Map.of(
                "model", modelName,
                "query", query,
                "documents", documents,
                "top_n", documents.size()
        );

        // 调用 Reranker API
        RerankResponse response = restClient.post()
                .uri("/rerank")
                .body(requestBody)
                .retrieve()
                .body(RerankResponse.class);

        if (response == null || response.results() == null) {
            log.warn("[Reranker] 响应为空，返回原始候选列表");
            return candidates;
        }

        log.debug("[Reranker] 精排完成，候选数={}，query={}", candidates.size(), query);

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

    /**
     * Reranker 服务熔断降级：返回原始候选列表，不影响检索可用性。
     */
    @SuppressWarnings("unused")
    private List<ChunkHit> rerankFallback(String query, List<ChunkHit> candidates, Throwable t) {
        log.warn("[Reranker] 精排服务不可用，使用原始候选列表，原因: {}", t.getMessage());
        return candidates;
    }

    // ---- API 响应 DTO（内部使用 record） ----

    private record RerankResponse(List<RerankResult> results) {}

    /** Reranker 响应中单条结果，relevance_score 是 snake_case，通过 @JsonProperty 映射 */
    private record RerankResult(
            int index,
            @com.fasterxml.jackson.annotation.JsonProperty("relevance_score") double relevanceScore
    ) {}
}
