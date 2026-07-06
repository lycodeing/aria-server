package com.aria.knowledge.infrastructure.embedding;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.infrastructure.config.EmbeddingProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAI 兼容协议 Embedding 服务实现（真实 HTTP 调用）。
 *
 * <p>自动替代 {@link StubEmbeddingService}（bean 名 {@code realEmbeddingService}）。
 *
 * <p>模型配置（baseUrl / apiKey / modelName）通过 {@link AiModelConfigProvider#getActiveEmbedding()}
 * 动态拉取，支持后台热切换，无需重启服务。
 *
 * <p>兼容协议：OpenAI {@code /embeddings} 端点，适配 infinity / Ollama / 智谱 / OpenAI 等主流服务。
 *
 * <pre>
 * POST {baseUrl}/embeddings
 * Authorization: Bearer {apiKey}        （apiKey 为空时省略此头，适合本地无鉴权服务）
 * { "model": "bge-m3", "input": ["text1", "text2", ...] }
 *
 * 响应：{ "data": [{"index": 0, "embedding": [0.1, ...]}, ...] }
 * </pre>
 *
 * <p>分批策略：按 {@code knowledge.embedding.batch-size}（默认 32）分批调用，
 * 防止单批文本量过大导致 OOM 或 HTTP 超时。
 */
@Slf4j
@Service("realEmbeddingService")
@Profile("!test")  // 测试环境由 StubEmbeddingService 接管，避免依赖外部 HTTP 服务
public class OpenAiEmbeddingService implements EmbeddingService {

    private final AiModelConfigProvider      configProvider;
    private final EmbeddingProperties        props;
    /** 按 baseUrl 缓存 RestClient，每个服务地址只初始化一个 HTTP 连接池 */
    private final ConcurrentHashMap<String, RestClient> clientCache = new ConcurrentHashMap<>();

    public OpenAiEmbeddingService(AiModelConfigProvider configProvider,
                                  EmbeddingProperties props) {
        this.configProvider = configProvider;
        this.props          = props;
    }

    /**
     * 批量向量化：按 batchSize 分批调用 Embedding API，就地填充 chunk.vector。
     * 每批重新拉取一次配置，支持在摄取过程中热切换模型（长文档摄取场景）。
     */
    @Override
    public void embed(List<KnowledgeChunk> chunks) {
        if (chunks.isEmpty()) return;

        List<List<KnowledgeChunk>> batches = partition(chunks, props.batchSize());
        log.debug("[Embedding] 开始批量向量化，总 chunk 数={}，分 {} 批", chunks.size(), batches.size());

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<KnowledgeChunk> batch = batches.get(batchIdx);
            AiModelConfig cfg = configProvider.getActiveEmbedding();

            List<String> texts = batch.stream()
                    .map(KnowledgeChunk::getContent)
                    .toList();

            List<float[]> vectors = callEmbeddingApi(cfg, texts);

            // 按 index 回填向量（API 响应顺序可能与输入不一致，以 index 字段为准）
            for (int i = 0; i < batch.size() && i < vectors.size(); i++) {
                batch.get(i).setVector(vectors.get(i));
            }

            log.debug("[Embedding] 第 {}/{} 批完成，model={} batch_size={}",
                    batchIdx + 1, batches.size(), cfg.modelName(), batch.size());
        }

        log.debug("[Embedding] 批量向量化全部完成，chunk 数={}", chunks.size());
    }

    /**
     * 单文本编码（检索时对 query 向量化）。
     * 每次调用均拉取最新配置，保证检索与摄取使用同一模型。
     */
    @Override
    public float[] encode(String text) {
        AiModelConfig cfg = configProvider.getActiveEmbedding();
        List<float[]> vectors = callEmbeddingApi(cfg, List.of(text));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("[Embedding] encode 返回空向量，text=" + text);
        }
        return vectors.get(0);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /**
     * 调用 OpenAI 兼容 /embeddings 接口，返回按 index 排序的向量列表。
     *
     * @param cfg   当前激活的 Embedding 模型配置
     * @param texts 待向量化文本列表（单批，已按 batchSize 分批）
     * @return 按原始输入顺序排列的向量列表
     */
    private List<float[]> callEmbeddingApi(AiModelConfig cfg, List<String> texts) {
        Map<String, Object> requestBody = Map.of(
                "model", cfg.modelName(),
                "input", texts
        );

        RestClient client = getOrCreateClient(cfg);
        RestClient.RequestBodySpec spec = client.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON);

        // apiKey 为空时不添加 Authorization 头（适配无鉴权的本地服务，如 infinity / Ollama）
        if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey());
        }

        EmbeddingResponse response = spec
                .body(requestBody)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException(
                    "[Embedding] API 返回空数据，model=" + cfg.modelName() + " baseUrl=" + cfg.baseUrl());
        }

        // 按 index 排序后返回向量列表，保证与输入 texts 的顺序一致
        return response.data().stream()
                .sorted((a, b) -> Integer.compare(a.index(), b.index()))
                .map(EmbeddingData::embedding)
                .toList();
    }

    /**
     * 按 baseUrl 缓存 RestClient，避免重复初始化 HTTP 连接池。
     * timeout 来自 EmbeddingProperties，每次取客户端时若 baseUrl 相同则复用。
     */
    private RestClient getOrCreateClient(AiModelConfig cfg) {
        return clientCache.computeIfAbsent(cfg.baseUrl(), baseUrl -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
            log.info("[Embedding] 初始化 HTTP 客户端，baseUrl={} timeout={}s",
                    baseUrl, props.timeoutSeconds());
            return RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(factory)
                    .build();
        });
    }

    /** 将 List 按固定大小分片 */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }

    // ---- API 响应 DTO（内部使用 record） ----

    private record EmbeddingResponse(List<EmbeddingData> data) {}

    private record EmbeddingData(
            int     index,
            float[] embedding,
            @JsonProperty("object") String object  // "embedding"，仅用于反序列化校验
    ) {}
}
