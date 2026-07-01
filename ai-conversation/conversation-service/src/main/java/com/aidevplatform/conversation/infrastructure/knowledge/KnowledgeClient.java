package com.aidevplatform.conversation.infrastructure.knowledge;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * knowledge-service 内部检索客户端。
 * 调用 POST /internal/knowledge/search 执行混合检索（BM25 + 向量 + RRF 融合）。
 * 超时或服务不可用时返回空列表，不阻断对话主流程（降级策略）。
 *
 * <p>注意：{@link #search} 使用 {@code .block()} 阻塞调用，
 * 仅适用于 Spring MVC 阻塞线程上下文，禁止在响应式管道（Flux/Mono 算子）内调用。
 */
@Slf4j
@Component
public class KnowledgeClient {

    private final WebClient webClient;

    @Value("${knowledge.search.default-kb-id:default}")
    private String defaultKbId;

    @Value("${knowledge.search.top-k:5}")
    private int topK;

    @Value("${knowledge.search.timeout-seconds:5}")
    private int timeoutSeconds;

    /**
     * 注入 Spring Boot 自动配置的 WebClient.Builder，
     * 确保 Micrometer 指标、分布式追踪等能力自动集成。
     */
    public KnowledgeClient(
            WebClient.Builder builder,
            @Value("${knowledge.service.base-url:http://localhost:8081}") String baseUrl) {
        this.webClient = builder
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .clone()
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
            KnowledgeSearchResult result = webClient.post()
                    .uri("/internal/knowledge/search")
                    .bodyValue(Map.of(
                            "query", query,
                            "kbId",  defaultKbId,
                            "topK",  topK
                    ))
                    .retrieve()
                    .onStatus(
                            status -> status.is5xxServerError(),
                            resp -> resp.bodyToMono(String.class).map(body -> {
                                log.warn("[RAG] knowledge-service 5xx 错误: {}", body);
                                return new IllegalStateException("knowledge-service 5xx: " + body);
                            })
                    )
                    .onStatus(
                            status -> status.is4xxClientError(),
                            resp -> {
                                log.warn("[RAG] knowledge-service 4xx 错误，请检查请求参数");
                                return resp.createException();
                            }
                    )
                    .bodyToMono(KnowledgeSearchResult.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (result == null || result.getCode() != 0) {
                log.warn("[RAG] 知识库检索响应异常，code={}", result != null ? result.getCode() : "null");
                return List.of();
            }
            log.debug("[RAG] 检索命中 {} 条，query={}", result.hits().size(), query);
            return result.hits();
        } catch (Exception e) {
            // 知识库不可用时降级为纯 AI 回复，不向上抛出
            log.warn("[RAG] 知识库检索失败，降级为纯 AI 回复: {}", e.getMessage());
            return List.of();
        }
    }
}
