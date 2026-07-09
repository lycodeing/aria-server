package com.aria.conversation.infrastructure.websocket.cluster;

/**
 * WS 集群相关魔法字符串常量。
 *
 * <p>集中定义 RabbitMQ Exchange 名称和 Redis 锁 key 前缀，
 * 避免散落在多个类中重复硬编码。
 * WS presence Redis key 前缀见 {@link com.aria.conversation.infrastructure.cache.ConversationCacheKeys}。
 */
public final class WsClusterConstants {

    private WsClusterConstants() {}

    /** WS 跨 Pod 投递 Direct Exchange 名称 */
    public static final String WS_DELIVERY_EXCHANGE = "ws.delivery";

    /** KICK 模式分布式锁 key 前缀，格式：{@code ws:kick:agent:{agentId}} */
    public static final String KICK_LOCK_KEY_PREFIX = "ws:kick:agent:";
}
