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
 * <p>从 aria-auth {@code /internal/ai-models/active} 拉取当前激活配置，
 * Redis 缓存 5 分钟，通过 Pub/Sub 监听 {@code aria:config:ai-changed} 实现热更新。
 *
 * <p>降级策略：auth-service 不可用时抛出 {@link IllegalStateException}，
 * 调用方（如 ChatAppService）通过 onErrorResume 降级使用 yml 兜底配置。
 */
@Slf4j
@AutoConfiguration
public class RemoteAiModelConfigProvider implements AiModelConfigProvider, MessageListener {

    private static final String CACHE_KEY    = "aria:ai:model:active";
    private static final Duration CACHE_TTL  = Duration.ofMinutes(5);
    private static final String PUBSUB_TOPIC = "aria:config:ai-changed";

    private final RedisCacheHelper cache;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${aria.auth.internal-url:http://localhost:8083}")
    private String authInternalUrl;

    public RemoteAiModelConfigProvider(
            RedisCacheHelper cache,
            WebClient.Builder builder,
            ObjectProvider<org.springframework.data.redis.listener.RedisMessageListenerContainer> containerProvider) {
        this.cache     = cache;
        this.webClient = builder.clone().build();
        // 容器存在时注册 Pub/Sub 监听；未配置时跳过，配置变更需等待缓存 TTL 自然过期
        containerProvider.ifAvailable(c -> c.addMessageListener(this, new ChannelTopic(PUBSUB_TOPIC)));
    }

    @Override
    public AiModelConfig getActive() {
        AiModelConfig cached = cache.get(CACHE_KEY, AiModelConfig.class);
        if (cached != null) return cached;

        AiModelConfig config = fetchFromAuthService();
        cache.set(CACHE_KEY, config, CACHE_TTL);
        log.debug("[AiConfig] 已从 auth-service 拉取配置并缓存，model={}", config.modelName());
        return config;
    }

    @Override
    public void invalidate() {
        cache.delete(CACHE_KEY);
        log.info("[AiConfig] 缓存已失效，下次请求将重新拉取");
    }

    /** Redis Pub/Sub 回调：收到配置变更通知时清除本地缓存 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("[AiConfig] 收到配置变更通知，清除缓存");
        invalidate();
    }

    private AiModelConfig fetchFromAuthService() {
        try {
            String json = webClient.get()
                    .uri(authInternalUrl + "/internal/ai-models/active")
                    .retrieve()
                    .onStatus(
                            status -> status.value() == 404,
                            resp -> resp.createException().map(e ->
                                    new IllegalStateException("auth-service 无激活的 AI 模型配置，请在后台设置默认配置"))
                    )
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            if (json == null) {
                throw new IllegalStateException("auth-service 返回空响应体");
            }
            JsonNode root = objectMapper.readTree(json);
            if (root.path("code").asInt() != 200) {
                throw new IllegalStateException("auth-service 返回异常 code=" + root.path("code").asInt()
                        + " msg=" + root.path("msg").asText());
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
            log.error("[AiConfig] 拉取 AI 模型配置失败，auth-service={}", authInternalUrl, e);
            throw new IllegalStateException("拉取 AI 模型配置失败: " + e.getMessage(), e);
        }
    }
}
