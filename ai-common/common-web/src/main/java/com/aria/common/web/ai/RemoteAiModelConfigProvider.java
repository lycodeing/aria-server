package com.aria.common.web.ai;

import com.aria.common.web.redis.RedisCacheHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 远程 AI 模型配置提供者。
 *
 * <p>从 aria-auth 拉取当前激活配置（CHAT / EMBEDDING 各自独立），
 * Redis 缓存 5 分钟，通过 Pub/Sub 监听 {@code aria:config:ai-changed} 实现热更新。
 *
 * <p>降级策略：auth-service 不可用时抛出 {@link IllegalStateException}，
 * 调用方通过 onErrorResume 降级使用兜底配置。
 *
 * <p>Bug 修复（原版缺失）：调用 auth-service 内部接口时携带 X-Internal-Secret 请求头，
 * 原版缺少此头导致 auth-service 返回 403。
 */
@Slf4j
@AutoConfiguration
public class RemoteAiModelConfigProvider implements AiModelConfigProvider, MessageListener {

    private static final String CHAT_CACHE_KEY      = "aria:ai:model:active";
    private static final String EMBEDDING_CACHE_KEY = "aria:ai:model:embedding:active";
    private static final String ROUTER_CACHE_KEY    = "aria:ai:model:router:active";
    private static final Duration CACHE_TTL         = Duration.ofMinutes(5);
    private static final String PUBSUB_TOPIC        = "aria:config:ai-changed";

    private final RedisCacheHelper cache;
    private final WebClient        webClient;
    private final ObjectMapper     objectMapper;

    @Value("${aria.auth.internal-url:http://localhost:8083}")
    private String authInternalUrl;

    /** 内部服务密钥，与 auth-service 的 aria.internal.secret 保持一致 */
    @Value("${aria.internal.secret:change-this-in-production}")
    private String internalSecret;

    public RemoteAiModelConfigProvider(
            RedisCacheHelper cache,
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            ObjectProvider<org.springframework.data.redis.listener.RedisMessageListenerContainer> containerProvider) {
        this.cache        = cache;
        this.webClient    = builder.clone().build();
        this.objectMapper = objectMapper;
        // 容器存在时注册 Pub/Sub 监听；未配置时跳过，配置变更需等待缓存 TTL 自然过期
        containerProvider.ifAvailable(c -> c.addMessageListener(this, new ChannelTopic(PUBSUB_TOPIC)));
    }

    // ---- CHAT 配置 ----

    @Override
    public AiModelConfig getActive() {
        AiModelConfig cached = cache.get(CHAT_CACHE_KEY, AiModelConfig.class);
        if (cached != null) return cached;

        AiModelConfig config = fetchFromAuthService("/internal/ai-models/active");
        cache.set(CHAT_CACHE_KEY, config, CACHE_TTL);
        log.debug("[AiConfig] 已从 auth-service 拉取 CHAT 配置并缓存，model={}", config.modelName());
        return config;
    }

    @Override
    public void invalidate() {
        cache.delete(CHAT_CACHE_KEY);
        log.info("[AiConfig] CHAT 配置缓存已失效，下次请求将重新拉取");
    }

    // ---- EMBEDDING 配置 ----

    @Override
    public AiModelConfig getActiveEmbedding() {
        AiModelConfig cached = cache.get(EMBEDDING_CACHE_KEY, AiModelConfig.class);
        if (cached != null) return cached;

        AiModelConfig config = fetchFromAuthService("/internal/ai-models/active-embedding");
        cache.set(EMBEDDING_CACHE_KEY, config, CACHE_TTL);
        log.debug("[AiConfig] 已从 auth-service 拉取 EMBEDDING 配置并缓存，model={}", config.modelName());
        return config;
    }

    @Override
    public void invalidateEmbedding() {
        cache.delete(EMBEDDING_CACHE_KEY);
        log.info("[AiConfig] EMBEDDING 配置缓存已失效，下次请求将重新拉取");
    }

    // ---- ROUTER 配置 ----

    @Override
    public AiModelConfig getActiveRouter() {
        AiModelConfig cached = cache.get(ROUTER_CACHE_KEY, AiModelConfig.class);
        if (cached != null) return cached;

        AiModelConfig config = fetchFromAuthService("/internal/ai-models/active-router");
        cache.set(ROUTER_CACHE_KEY, config, CACHE_TTL);
        log.debug("[AiConfig] 已从 auth-service 拉取 ROUTER 配置并缓存，model={}", config.modelName());
        return config;
    }

    @Override
    public void invalidateRouter() {
        cache.delete(ROUTER_CACHE_KEY);
        log.info("[AiConfig] ROUTER 配置缓存已失效，下次请求将重新拉取");
    }

    // ---- Redis Pub/Sub ----

    /** 配置变更通知：同时失效 CHAT + EMBEDDING + ROUTER 三个缓存 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("[AiConfig] 收到配置变更通知，清除 CHAT + EMBEDDING + ROUTER 缓存");
        invalidate();
        invalidateEmbedding();
        invalidateRouter();
    }

    // ---- 内部工具 ----

    /**
     * 调用 auth-service 内部接口拉取配置。
     *
     * <p>携带 X-Internal-Secret 请求头（原版缺失此头导致 403）。
     *
     * @param path 接口路径，如 /internal/ai-models/active
     */
    private AiModelConfig fetchFromAuthService(String path) {
        try {
            String json = webClient.get()
                    .uri(authInternalUrl + path)
                    // ⚠️ 内部密钥：auth-service 校验此头，缺失时返回 403
                    .header("X-Internal-Secret", internalSecret)
                    .retrieve()
                    .onStatus(
                            status -> status.value() == 404,
                            resp -> resp.createException().map(e ->
                                    new IllegalStateException("auth-service 无激活配置，请在后台设置默认配置"))
                    )
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            if (json == null) {
                throw new IllegalStateException("auth-service 返回空响应体，path=" + path);
            }
            JsonNode root = objectMapper.readTree(json);
            if (root.path("code").asInt() != 200) {
                throw new IllegalStateException("auth-service 返回异常 code=" + root.path("code").asInt()
                        + " msg=" + root.path("msg").asText() + " path=" + path);
            }
            JsonNode data = root.path("data");
            return new AiModelConfig(
                    data.path("id").asLong(),
                    data.path("name").asText(),
                    data.path("provider").asText(),
                    data.path("apiProtocol").asText("OPENAI_COMPATIBLE"),
                    data.path("baseUrl").asText(),
                    data.path("apiKey").asText(),
                    data.path("modelName").asText(),
                    data.path("temperature").asDouble(0.7),
                    data.path("maxTokens").asInt(2048),
                    data.path("timeoutSec").asInt(60)
            );
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AiConfig] 拉取配置失败，auth-service={} path={}", authInternalUrl, path, e);
            throw new IllegalStateException("拉取 AI 配置失败: " + e.getMessage(), e);
        }
    }
}
