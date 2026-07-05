package com.aria.knowledge.infrastructure.embedding;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.infrastructure.config.EmbeddingProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 LangChain4j 的 Embedding 服务，替代手写 {@link OpenAiEmbeddingService}。
 * 支持任意 OpenAI 兼容端点，Caffeine 缓存按 config hash 热切换。
 */
@Slf4j
@Service("realEmbeddingService")
@Profile("!test")
public class LangChain4jEmbeddingService implements EmbeddingService {

    private final AiModelConfigProvider configProvider;
    private final EmbeddingProperties   props;

    /** 按配置 hash 缓存 EmbeddingModel，支持热切换（不同 baseUrl/modelName/apiKey 各自独立实例） */
    private final Cache<String, EmbeddingModel> modelCache = Caffeine.newBuilder()
            .maximumSize(5)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    public LangChain4jEmbeddingService(AiModelConfigProvider configProvider,
                                       EmbeddingProperties props) {
        this.configProvider = configProvider;
        this.props          = props;
    }

    /**
     * 批量向量化：按 batchSize 分批，每批重拉配置以支持长任务热切换，就地填充 chunk.vector。
     */
    @Override
    public void embed(List<KnowledgeChunk> chunks) {
        if (chunks.isEmpty()) return;

        List<List<KnowledgeChunk>> batches = partition(chunks, props.batchSize());
        log.debug("[Embedding] 开始批量向量化 total={} batches={}", chunks.size(), batches.size());

        for (int i = 0; i < batches.size(); i++) {
            List<KnowledgeChunk> batch = batches.get(i);
            EmbeddingModel model = getModel();   // 每批重拉，支持运行期切换配置

            List<TextSegment> segments = batch.stream()
                    .map(c -> TextSegment.from(c.getContent()))
                    .toList();

            List<Embedding> embeddings = model.embedAll(segments).content();

            for (int j = 0; j < batch.size() && j < embeddings.size(); j++) {
                batch.get(j).setVector(embeddings.get(j).vector());
            }
            log.debug("[Embedding] 第 {}/{} 批完成 batchSize={}", i + 1, batches.size(), batch.size());
        }
        log.debug("[Embedding] 批量向量化完成 total={}", chunks.size());
    }

    /**
     * 单文本编码（检索时对 query 向量化）。
     */
    @Override
    public float[] encode(String text) {
        return getModel().embed(TextSegment.from(text)).content().vector();
    }

    /**
     * 测试专用：将 mock 模型注入缓存，绕过真实 HTTP 调用。
     * 使用与 {@link #getModel()} 相同的 key，保证 embed/encode 都能命中。
     */
    void overrideModelForTest(EmbeddingModel model) {
        AiModelConfig cfg = configProvider.getActiveEmbedding();
        modelCache.put(configKey(cfg), model);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    private EmbeddingModel getModel() {
        AiModelConfig cfg = configProvider.getActiveEmbedding();
        return modelCache.get(configKey(cfg), k -> {
            log.info("[Embedding] 初始化 EmbeddingModel baseUrl={} model={}", cfg.baseUrl(), cfg.modelName());
            return OpenAiEmbeddingModel.builder()
                    .baseUrl(cfg.baseUrl())
                    .apiKey(cfg.apiKey() != null && !cfg.apiKey().isBlank()
                            ? cfg.apiKey() : "none")
                    .modelName(cfg.modelName())
                    .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                    .build();
        });
    }

    /**
     * 生成配置缓存 key — 包含 baseUrl、modelName 和 apiKey（均影响路由和鉴权）。
     * 不打印到日志（apiKey 敏感）。
     */
    private static String configKey(AiModelConfig cfg) {
        return cfg.baseUrl() + "|" + cfg.modelName() + "|"
                + (cfg.apiKey() != null ? cfg.apiKey() : "");
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return parts;
    }
}
