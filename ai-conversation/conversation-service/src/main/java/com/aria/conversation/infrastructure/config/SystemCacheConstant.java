package com.aria.conversation.infrastructure.config;

/**
 * 系统级 Redis 缓存 Key 与 Pub/Sub 主题常量。
 *
 * <p><b>适用范围：</b>收录系统基础设施层使用的 Redis 缓存 Key，
 * 目前主要包含 AI 模型配置的三类缓存 Key 及其对应的变更通知主题。
 *
 * <p><b>与客服业务常量的区别：</b>
 * <ul>
 *   <li>本类收录的是 <b>Redis</b> 缓存 Key，由 {@code RedisCacheHelper} 统一管理，
 *       支持 TTL 自动过期和 Pub/Sub 主动失效两种策略</li>
 *   <li>{@link CustomerServiceCacheConstant} 收录的是 <b>Caffeine</b> 本地缓存 Key，
 *       仅在当前 JVM 进程内有效，只支持 TTL 过期</li>
 * </ul>
 *
 * <p><b>Key 命名规范：</b>{@code aria:{模块}:{资源}:{状态}}，
 * 如 {@code aria:ai:model:active}，与 auth-service 侧保持严格一致。
 *
 * <p><b>维护说明：</b>新增系统级 Redis 缓存 Key 时，须同步修改 auth-service 侧的对应常量，
 * 两端保持字符串完全一致，避免缓存失效失效（key 不匹配时 Pub/Sub 清缓存无效）。
 *
 * @see com.aria.common.web.ai.RemoteAiModelConfigProvider
 */
public final class SystemCacheConstant {

    // ============================================================
    // AI 模型配置 Redis 缓存 Key
    // 由 common-web 模块的 RemoteAiModelConfigProvider 读写
    // 缓存内容：序列化后的 AiModelConfig 对象
    // 缓存 TTL：5 分钟，支持 Pub/Sub 主动失效
    // ============================================================

    /**
     * 对话大模型（CHAT）当前激活配置的 Redis 缓存 Key。
     *
     * <p>存储序列化后的 {@code AiModelConfig}（CHAT 类型），
     * 由 {@code RemoteAiModelConfigProvider#getActive()} 读取，
     * 由 {@code RemoteAiModelConfigProvider#invalidate()} 主动清除。
     *
     * <p>对应 auth-service {@code cs_auth.ai_model_config} 表中
     * {@code model_type = 'CHAT'} 且 {@code is_default = true} 的记录。
     */
    public static final String AI_MODEL_CHAT_ACTIVE = "aria:ai:model:active";

    /**
     * 向量模型（EMBEDDING）当前激活配置的 Redis 缓存 Key。
     *
     * <p>存储序列化后的 {@code AiModelConfig}（EMBEDDING 类型），
     * 由 {@code RemoteAiModelConfigProvider#getActiveEmbedding()} 读取，
     * 由 {@code RemoteAiModelConfigProvider#invalidateEmbedding()} 主动清除。
     *
     * <p>对应 auth-service {@code cs_auth.ai_model_config} 表中
     * {@code model_type = 'EMBEDDING'} 且 {@code is_default = true} 的记录。
     */
    public static final String AI_MODEL_EMBEDDING_ACTIVE = "aria:ai:model:embedding:active";

    /**
     * 域路由小模型（ROUTER）当前激活配置的 Redis 缓存 Key。
     *
     * <p>存储序列化后的 {@code AiModelConfig}（ROUTER 类型），
     * 由 {@code RemoteAiModelConfigProvider#getActiveRouter()} 读取，
     * 由 {@code RemoteAiModelConfigProvider#invalidateRouter()} 主动清除。
     *
     * <p>对应 auth-service {@code cs_auth.ai_model_config} 表中
     * {@code model_type = 'ROUTER'} 且 {@code is_default = true} 的记录。
     */
    public static final String AI_MODEL_ROUTER_ACTIVE = "aria:ai:model:router:active";

    // ============================================================
    // Redis Pub/Sub 变更通知主题
    // ============================================================

    /**
     * AI 模型配置变更通知的 Redis Pub/Sub 主题。
     *
     * <p><b>发布方：</b>auth-service 在管理员修改 AI 模型配置后发布此主题消息。
     *
     * <p><b>订阅方：</b>{@code RemoteAiModelConfigProvider} 订阅此主题，
     * 收到消息后同时清除 {@link #AI_MODEL_CHAT_ACTIVE}、
     * {@link #AI_MODEL_EMBEDDING_ACTIVE}、{@link #AI_MODEL_ROUTER_ACTIVE}
     * 三个 Redis 缓存，确保下次读取时从 auth-service 拉取最新配置。
     *
     * <p><b>消息内容：</b>通知内容不作解析，仅作触发信号使用。
     */
    public static final String AI_MODEL_CHANGED_TOPIC = "aria:config:ai-changed";

    /**
     * 工具类，禁止实例化。
     */
    private SystemCacheConstant() {
        throw new UnsupportedOperationException("SystemCacheConstant is a utility class");
    }
}
