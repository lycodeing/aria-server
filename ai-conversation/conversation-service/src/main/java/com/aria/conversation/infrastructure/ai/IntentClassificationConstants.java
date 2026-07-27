package com.aria.conversation.infrastructure.ai;

/**
 * 意图分类相关默认值常量。
 *
 * <p>所有默认值均可通过 {@code system_config} 的 {@code routing.config} 覆盖，
 * 此处仅为 Java 侧 {@link com.aria.conversation.infrastructure.ai.RoutingConfig.Intent}
 * 字段的默认值来源。
 * 阿里规范：不允许在代码中直接使用魔法值。
 */
public interface IntentClassificationConstants {
    /** Tier2 Embedding 全局默认相似度阈值 */
    double DEFAULT_EMBEDDING_THRESHOLD      = 0.75;
    /** Tier2 高置信度阈值：超过此值跳过 Tier3 LLM */
    double DEFAULT_HIGH_CONFIDENCE          = 0.85;
    /** Tier3 LLM 最低置信度：低于此值的意图被过滤 */
    double DEFAULT_MIN_LLM_CONFIDENCE       = 0.50;
    /** 高置信度自动积累历史案例的最低置信度门槛 */
    double DEFAULT_AUTO_ACCUMULATE_MIN_CONF = 0.95;
    /** Caffeine 本地缓存最大条目数（意图原型）*/
    int    PROTOTYPE_CACHE_MAX_SIZE         = 200;
    /** Caffeine 本地缓存 TTL（分钟）*/
    int    PROTOTYPE_CACHE_TTL_MINUTES      = 10;
    /** LLM Few-Shot 静态样本最大注入数 */
    int    DEFAULT_MAX_EXAMPLES_TO_INJECT   = 3;
    /** Tier3 动态 RAG 每意图注入历史案例数 */
    int    DEFAULT_LLM_RAG_TOP_K            = 2;
}
