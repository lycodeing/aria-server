package com.aria.conversation.infrastructure.embedding;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 基于 LangChain4j 的 Embedding 服务实现。
 *
 * <p>使用 {@link OpenAiEmbeddingModel} 适配任意 OpenAI 兼容端点，
 * Caffeine 缓存按 config hash 热切换（不同 baseUrl/modelName 各自独立实例）。
 */
@Slf4j
@Service
@Profile("!test")
@RequiredArgsConstructor
public class LangChain4jEmbeddingService implements EmbeddingService {

    private final AiModelConfigProvider configProvider;

    /** 按配置 hash 缓存 EmbeddingModel，支持热切换 */
    private final Cache<String, EmbeddingModel> modelCache = Caffeine.newBuilder()
            .maximumSize(5)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    @Override
    public float[] encode(String text) {
        AiModelConfig config = configProvider.getActiveEmbedding();
        String key = configHash(config);
        EmbeddingModel model = modelCache.get(key, k -> buildModel(config));

        Embedding embedding = model.embed(TextSegment.from(text)).content();
        return embedding.vector();
    }

    private EmbeddingModel buildModel(AiModelConfig config) {
        log.debug("[Embedding] 构建 EmbeddingModel baseUrl={} model={}",
                config.baseUrl(), config.modelName());
        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.modelName())
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private String configHash(AiModelConfig config) {
        String raw = config.baseUrl() + "|" + config.modelName() + "|" + config.apiKey();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return new String(digest, StandardCharsets.ISO_8859_1);
        } catch (NoSuchAlgorithmException e) {
            return raw;
        }
    }
}
