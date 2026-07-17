package com.aria.conversation.infrastructure.config;

import com.aria.sdk.auth.AuthClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 客服接待配置提供者。
 *
 * <p>从 auth-service system_config 表读取 {@code cs.agent.config} JSON，
 * Caffeine 本地缓存 TTL 5 分钟，auth-service 不可用时降级返回默认值（maxSessionsPerAgent=5）。
 * 降级值不写入缓存，下次请求重新尝试拉取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CsAgentConfigProvider {

    private final AuthClient    authClient;
    private final ObjectMapper  objectMapper;

    private final Cache<String, CsAgentConfig> localCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    public CsAgentConfig getConfig() {
        CsAgentConfig cached = localCache.getIfPresent(CustomerServiceCacheConstant.CS_AGENT_CONFIG);
        if (cached != null) return cached;
        try {
            String json = authClient.getSystemConfigValue(CustomerServiceCacheConstant.CS_AGENT_CONFIG);
            if (json != null && !json.isBlank()) {
                CsAgentConfig config = objectMapper.readValue(json, CsAgentConfig.class);
                localCache.put(CustomerServiceCacheConstant.CS_AGENT_CONFIG, config);
                return config;
            }
        } catch (Exception e) {
            log.warn("[CsAgentConfig] 拉取配置失败，使用默认值 maxSessionsPerAgent=5", e);
        }
        return CsAgentConfig.defaults(); // 不缓存降级值
    }

    /** 便捷方法：直接获取 maxSessionsPerAgent */
    public int getMaxSessionsPerAgent() {
        return getConfig().getMaxSessionsPerAgent();
    }
}
