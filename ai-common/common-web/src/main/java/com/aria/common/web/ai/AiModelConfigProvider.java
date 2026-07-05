package com.aria.common.web.ai;

/**
 * AI 模型配置提供者接口。
 * 实现类负责缓存管理和从 auth-service 拉取最新配置。
 *
 * <p>CHAT 和 EMBEDDING 配置独立管理，各自有独立缓存和失效方法。
 */
public interface AiModelConfigProvider {

    /**
     * 获取当前激活（默认）的 CHAT 模型配置。
     * 优先读 Redis 缓存（TTL 5 分钟），缓存未命中时调用 auth-service 内部接口。
     *
     * @return 当前激活的 CHAT 模型配置，永不为 null
     * @throws IllegalStateException auth-service 不可用且无缓存时抛出
     */
    AiModelConfig getActive();

    /**
     * 获取当前激活（默认）的 EMBEDDING 模型配置。
     * 优先读 Redis 缓存（TTL 5 分钟），缓存未命中时调用 auth-service 内部接口。
     *
     * @return 当前激活的 EMBEDDING 模型配置，永不为 null
     * @throws IllegalStateException auth-service 不可用且无缓存时抛出
     */
    AiModelConfig getActiveEmbedding();

    /**
     * 主动失效 CHAT 配置本地缓存（收到 Redis Pub/Sub 通知时调用）。
     */
    void invalidate();

    /**
     * 主动失效 EMBEDDING 配置本地缓存（收到 Redis Pub/Sub 通知时调用）。
     */
    void invalidateEmbedding();

    /**
     * 获取当前激活（默认）的 ROUTER 模型配置（域路由小模型）。
     * 优先读 Redis 缓存（TTL 5 分钟），缓存未命中时调用 auth-service 内部接口。
     *
     * @return 当前激活的 ROUTER 模型配置，永不为 null
     * @throws IllegalStateException auth-service 不可用且无缓存时抛出
     */
    AiModelConfig getActiveRouter();

    /**
     * 主动失效 ROUTER 配置本地缓存（收到 Redis Pub/Sub 通知时调用）。
     */
    void invalidateRouter();
}
