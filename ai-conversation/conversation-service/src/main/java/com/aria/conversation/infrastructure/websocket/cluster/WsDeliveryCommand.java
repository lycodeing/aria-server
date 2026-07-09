package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.conversation.infrastructure.websocket.message.WsChatMessage;
import com.aria.conversation.infrastructure.websocket.message.WsConnectedMessage;
import com.aria.conversation.infrastructure.websocket.message.WsErrorMessage;
import com.aria.conversation.infrastructure.websocket.message.WsMessageType;
import com.aria.conversation.infrastructure.websocket.message.WsTypingMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 跨 Pod WS 投递命令，序列化为 JSON 后通过 RabbitMQ {@code ws.delivery} Direct Exchange 传递。
 *
 * <p>payload 序列化策略：使用 {@code wsMessageType + payloadJson} 两字段，
 * 避免 Jackson 反序列化 {@code Object} 类型擦除为 {@link java.util.LinkedHashMap}。
 * 发送端预序列化，消费端按 {@link WsMessageType} 枚举 switch 还原为具体 record 类型。
 *
 * <p>KICK_AGENT 命令无 payload，消费端收到后直接本地构造
 * {@link com.aria.conversation.infrastructure.websocket.message.WsKickedOutMessage#INSTANCE} 广播。
 *
 * @param targetType         投递目标类型：AGENT / VISITOR / KICK_AGENT
 * @param targetId           投递目标 ID（agentId 或 sessionId）
 * @param wsMessageType      消息类型（KICK_AGENT 时为 null）
 * @param payloadJson        payload JSON 字符串（KICK_AGENT 时为 null）
 * @param excludeWsSessionId KICK_AGENT 时记录新连接 wsSessionId，仅供日志，可为 null
 */
public record WsDeliveryCommand(
        TargetType    targetType,
        String        targetId,
        WsMessageType wsMessageType,
        String        payloadJson,
        String        excludeWsSessionId
) {

    /** 投递目标类型 */
    public enum TargetType { AGENT, VISITOR, KICK_AGENT }

    /**
     * 向座席投递消息。序列化失败时抛 {@link IllegalStateException}（编程错误）。
     */
    public static WsDeliveryCommand toAgent(String agentId, Object payload, ObjectMapper om) {
        return build(TargetType.AGENT, agentId, payload, om);
    }

    /**
     * 向访客投递消息。
     */
    public static WsDeliveryCommand toVisitor(String sessionId, Object payload, ObjectMapper om) {
        return build(TargetType.VISITOR, sessionId, payload, om);
    }

    /**
     * KICK 命令：通知目标 Pod 关闭该 agentId 的所有旧连接。
     * {@code excludeWsSessionId} 为新连接 wsSessionId，仅供日志追踪。
     */
    public static WsDeliveryCommand kickAgent(String agentId, String excludeWsSessionId) {
        return new WsDeliveryCommand(TargetType.KICK_AGENT, agentId, null, null, excludeWsSessionId);
    }

    private static WsDeliveryCommand build(TargetType type, String targetId,
                                           Object payload, ObjectMapper om) {
        WsMessageType msgType = extractMessageType(payload);
        try {
            return new WsDeliveryCommand(type, targetId, msgType,
                    om.writeValueAsString(payload), null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "WS payload 序列化失败: " + payload.getClass().getSimpleName(), e);
        }
    }

    /**
     * 从 payload 对象提取 {@link WsMessageType} 枚举。
     *
     * <p>注意：{@link WsMessageType#KICKED_OUT} 不在此方法，这是故意的。
     * KICK 走 {@link TargetType#KICK_AGENT} 路径，消费端直接构造 WsKickedOutMessage.INSTANCE，
     * 无需跨 Pod 传输 payload。
     */
    private static WsMessageType extractMessageType(Object payload) {
        if (payload instanceof WsChatMessage)      return WsMessageType.MESSAGE;
        if (payload instanceof WsTypingMessage)    return WsMessageType.TYPING;
        if (payload instanceof WsConnectedMessage) return WsMessageType.CONNECTED;
        if (payload instanceof WsErrorMessage)     return WsMessageType.ERROR;
        throw new IllegalArgumentException(
                "不支持跨 Pod 投递的消息类型: " + payload.getClass().getSimpleName());
    }
}
