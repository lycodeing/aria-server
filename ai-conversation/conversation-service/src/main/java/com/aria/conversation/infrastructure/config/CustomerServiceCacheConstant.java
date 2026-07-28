package com.aria.conversation.infrastructure.config;

/**
 * 客服业务本地缓存 Key 常量（Caffeine）。
 *
 * <p>仅收录本服务内 Caffeine 本地缓存使用的 key，
 * Redis 缓存 key 由各自的 Provider/Repository 私有管理，不在此维护。
 *
 * <p>命名规范：{模块}.{资源}，与 system_config.config_key 保持一致（如适用）。
 * 禁止在代码中直接使用字符串字面量作为缓存键。
 */
public final class CustomerServiceCacheConstant {

    /**
     * 路由阈值配置缓存键，对应 system_config.config_key = 'routing.config'。
     * 由 {@code RoutingConfigProvider} 使用，缓存序列化后的 {@code RoutingConfig} 对象。
     */
    public static final String ROUTING_CONFIG = "routing.config";

    /**
     * 意图规则列表缓存键（Caffeine 内部 key，无对应 DB 记录）。
     * 由 {@code KeywordRegexIntentMatcher} 使用，缓存编译后的意图规则列表。
     */
    public static final String INTENT_RULES = "rules";

    /**
     * 域路由规则列表缓存键（Caffeine 内部 key，无对应 DB 记录）。
     * 由 {@code KeywordRegexDomainMatcher} 使用，缓存编译后的域规则列表。
     */
    public static final String DOMAIN_RULES = "domain_rules";

    /**
     * Tier2 意图原型向量 Redis HASH key。
     * field=intentCode，value=PrototypeEntry JSON（含 vector/exampleCount/updatedAt）。
     * 由 {@code IntentPrototypeStore} 使用。
     */
    public static final String INTENT_PROTOTYPES = "intent:prototypes";

    /**
     * 原型向量版本号 Redis key，IntentConfig 变更时更新。
     * 由 {@code IntentPrototypeStore} 使用，供感知配置变更。
     */
    public static final String INTENT_PROTOTYPE_VERSION = "intent:prototype:version";

    private CustomerServiceCacheConstant() {
        throw new UnsupportedOperationException("CustomerServiceCacheConstant is a utility class");
    }
}
