package com.aria.conversation.infrastructure.ai;

import com.aria.sdk.auth.AuthClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 路由阈值配置提供者。
 *
 * <p>从 auth-service system_config 表读取 {@code routing.config} JSON 并反序列化为
 * {@link RoutingConfig}，Caffeine 本地缓存 TTL 5 分钟。
 * auth-service 不可用时降级返回 {@link RoutingProperties} YAML 默认值构造的配置对象。
 *
 * <p>运营在管理后台修改配置后，最多 5 分钟内自动生效，无需手动刷新或重启。
 *
 * <p>调用方示例：
 * <pre>{@code
 * double threshold = routingConfigProvider.getConfig().getIntent().getMinLlmConfidence();
 * boolean enabled  = routingConfigProvider.getConfig().getDomain().isRuleEnabled();
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoutingConfigProvider {

    /** auth-service SDK 客户端，用于拉取 system_config 表中的配置值 */
    private final AuthClient        authClient;
    /** JSON 反序列化工具，将 routing.config 字符串转换为 {@link RoutingConfig} 对象 */
    private final ObjectMapper      objectMapper;
    /** YAML 绑定的默认配置，auth-service 不可用时作为降级兜底，永远不为 null */
    private final RoutingProperties defaults;

    /**
     * Caffeine 本地缓存，单条记录，TTL 5 分钟。
     * maximumSize=1 确保内存占用可预期；TTL 过期后由 Caffeine 自动触发重新加载。
     */
    private final Cache<String, RoutingConfig> localCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    /**
     * 获取当前路由配置。缓存命中直接返回，未命中时从 auth-service 拉取并缓存。
     *
     * @return {@link RoutingConfig}，auth-service 不可用时返回 YAML 默认值
     */
    public RoutingConfig getConfig() {
        return localCache.get(CustomerServiceCacheConstant.ROUTING_CONFIG, k -> load());
    }

    /**
     * 从 auth-service 拉取配置并反序列化，失败时降级为 YAML 默认值。
     * 降级时不写缓存，下次请求重新尝试拉取。
     */
    private RoutingConfig load() {
        try {
            String json = authClient.getSystemConfigValue(
                    CustomerServiceCacheConstant.ROUTING_CONFIG);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, RoutingConfig.class);
            }
        } catch (Exception e) {
            log.warn("[RoutingConfig] 拉取配置失败，降级使用 YAML 默认值", e);
        }
        return RoutingConfig.fromProperties(defaults);
    }
}
