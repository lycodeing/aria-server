package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.sdk.auth.AuthClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 路由配置提供者。
 *
 * <p>从 auth-service system_config 读取 {@code routing.config} 单条 JSON 记录，
 * Redis 缓存 TTL 5 分钟。通过 Redis Pub/Sub {@code aria:config:routing-changed}
 * 主题接收变更通知，主动清缓存。auth-service 不可用时降级返回 {@link RoutingProperties} YAML 默认值。
 *
 * <p>Pub/Sub 注册方式与 {@code RemoteAiModelConfigProvider} 保持一致：
 * 通过 {@link ObjectProvider} 延迟获取 {@link RedisMessageListenerContainer}，
 * 容器不存在时跳过注册，配置变更需等待缓存 TTL 自然过期。
 */
@Slf4j
@Component
public class RoutingConfigProvider implements MessageListener {

    public static final String PUBSUB_TOPIC = "aria:config:routing-changed";
    private static final String CACHE_KEY   = "aria:routing:config";
    private static final String CONFIG_KEY  = "routing.config";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final RedisCacheHelper cache;
    private final AuthClient authClient;
    private final ObjectMapper objectMapper;
    private final RoutingProperties defaults;

    public RoutingConfigProvider(
            RedisCacheHelper cache,
            AuthClient authClient,
            ObjectMapper objectMapper,
            RoutingProperties defaults,
            ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        this.cache        = cache;
        this.authClient   = authClient;
        this.objectMapper = objectMapper;
        this.defaults     = defaults;
        // 容器存在时注册 Pub/Sub 监听；未配置时跳过，配置变更需等待缓存 TTL 自然过期
        containerProvider.ifAvailable(c -> c.addMessageListener(this, new ChannelTopic(PUBSUB_TOPIC)));
    }

    // ---- 读取接口 ----

    public boolean isEmbeddingEnabled() {
        return getBoolean("intent.embeddingEnabled", defaults.getIntent().isEmbeddingEnabled());
    }

    public double getEmbeddingThreshold() {
        return getDouble("intent.embeddingThreshold", defaults.getIntent().getEmbeddingThreshold());
    }

    public double getMinLlmConfidence() {
        return getDouble("intent.minLlmConfidence", defaults.getIntent().getMinLlmConfidence());
    }

    public int getMaxExamplesToInject() {
        return getInt("intent.maxExamplesToInject", defaults.getIntent().getMaxExamplesToInject());
    }

    public boolean isDomainRuleEnabled() {
        return getBoolean("domain.ruleEnabled", defaults.getDomain().isRuleEnabled());
    }

    // ---- Pub/Sub 失效 ----

    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("[RoutingConfig] 收到配置变更通知，清除路由配置缓存");
        cache.delete(CACHE_KEY);
    }

    // ---- 内部工具 ----

    private JsonNode configNode() {
        return cache.getOrLoad(CACHE_KEY, JsonNode.class, CACHE_TTL, () -> {
            try {
                String json = authClient.getSystemConfigValue(CONFIG_KEY);
                if (json == null || json.isBlank()) return objectMapper.createObjectNode();
                return objectMapper.readTree(json);
            } catch (Exception e) {
                log.warn("[RoutingConfig] 拉取路由配置失败，降级使用 YAML 默认值", e);
                return objectMapper.createObjectNode();
            }
        });
    }

    private boolean getBoolean(String dotPath, boolean defaultValue) {
        JsonNode node = resolvePath(dotPath);
        return node.isMissingNode() ? defaultValue : node.asBoolean(defaultValue);
    }

    private double getDouble(String dotPath, double defaultValue) {
        JsonNode node = resolvePath(dotPath);
        return node.isMissingNode() ? defaultValue : node.asDouble(defaultValue);
    }

    private int getInt(String dotPath, int defaultValue) {
        JsonNode node = resolvePath(dotPath);
        return node.isMissingNode() ? defaultValue : node.asInt(defaultValue);
    }

    private JsonNode resolvePath(String dotPath) {
        JsonNode node = configNode();
        if (node == null) return objectMapper.missingNode();
        for (String part : dotPath.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode()) return node;
        }
        return node;
    }
}
