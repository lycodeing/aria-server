package com.aria.conversation.infrastructure.knowledge;

import com.aria.common.core.util.JsonUtils;
import com.aria.sdk.knowledge.KnowledgeClient;
import com.aria.sdk.knowledge.dto.SearchRequest;
import com.aria.sdk.knowledge.dto.SearchResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * knowledge-service 内部检索客户端。
 * 调用 POST /internal/knowledge/search 执行混合检索（BM25 + 向量 + RRF 融合）。
 * 超时或服务不可用时返回空列表，不阻断对话主流程（降级策略）。
 *
 * <p>注意：{@link #search} 使用 {@code .block()} 风格阻塞调用，
 * 仅适用于 Spring MVC 阻塞线程上下文，禁止在响应式管道（Flux/Mono 算子）内调用。
 */
@Slf4j
@Component
public class KnowledgeServiceClient {

    private final KnowledgeClient knowledgeClient;

    @Value("${knowledge.search.default-kb-id:default}")
    private String defaultKbId;

    @Value("${knowledge.search.top-k:5}")
    private int topK;

    /**
     * 通过 Builder 构建 KnowledgeClient，底层的 OkHttpClient 及鉴权拦截器
     * 由 KnowledgeClient 内部管理，无需额外注入 WebClient。
     */
    public KnowledgeServiceClient(
            @Value("${knowledge.service.base-url:http://localhost:8084}") String baseUrl,
            @Value("${aria.internal.secret:change-this-in-production}") String internalSecret,
            @Value("${knowledge.search.timeout-seconds:5}") int timeoutSeconds) {
        this.knowledgeClient = KnowledgeClient.builder()
                .baseUrl(baseUrl)
                .sharedSecret(internalSecret)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(0)
                .build();
    }

    /**
     * 启动校验：若 default-kb-id 仍为占位默认值，记录警告提示运维检查生产配置。
     */
    @PostConstruct
    void validate() {
        if ("default".equals(defaultKbId)) {
            log.warn("[RAG] knowledge.search.default-kb-id 使用了占位默认值 'default'，请检查生产配置");
        }
    }

    /**
     * 混合检索：返回与 query 最相关的 top-K chunks。
     *
     * <p>超时或异常时返回空列表，不阻断对话主流程。
     * <p>⚠️ 此方法使用 block()，仅限 Spring MVC 阻塞线程调用，禁止在响应式管道内调用。
     *
     * @param query 用户问题文本
     * @return 命中的 chunk 列表，失败时为空列表
     */
    public List<KnowledgeSearchResult.Hit> search(String query) {
        if (query == null || query.isBlank()) {
            log.debug("[RAG] query 为空，跳过知识库检索");
            return List.of();
        }

        try {
            SearchResponse searchResponse = knowledgeClient.search(SearchRequest.builder()
                    .query(query)
                    .kbId(defaultKbId)
                    .topK(topK)
                    .build());
            log.debug("[RAG] knowledge search response: {}", JsonUtils.toJsonString(searchResponse));
            return searchResponse.getHits().stream()
                    .map(hit -> KnowledgeSearchResult.Hit.builder()
                            .chunkId(hit.getChunkId())
                            .docId(hit.getDocId())
                            .content(hit.getContent())
                            .breadcrumb(hit.getBreadcrumb())
                            .score(hit.getScore())
                            .source(hit.getSource())
                            .build())
                    .toList();
        } catch (Exception e) {
            log.warn("[RAG] knowledge search error: {}", e.getMessage());
            return List.of();
        }
    }
}