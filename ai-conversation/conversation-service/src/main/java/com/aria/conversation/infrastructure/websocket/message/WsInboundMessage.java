package com.aria.conversation.infrastructure.websocket.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 客户端入站 WebSocket 消息。
 *
 * <p>适用于访客端（{@code /ws/chat/{sessionId}}）和座席端（{@code /ws/agent}）的入站消息。
 * 未知字段被忽略，保证协议向前兼容。
 *
 * <ul>
 *   <li>访客端：{@code sessionId} 来自 URL 路径参数，消息体中可不携带</li>
 *   <li>座席端：{@code sessionId} 必须在消息体中显式携带，用于路由到对应会话</li>
 * </ul>
 *
 * @param type      消息类型（{@link WsMessageType}，字符串形式；缺失时由 Handler 按 MESSAGE 处理）
 * @param sessionId 会话 ID（座席端必填；访客端可为 null，由 Handler 从 attributes 读取）
 * @param content   消息内容（TYPING 信令时为 null）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WsInboundMessage(
        String type,
        String sessionId,
        String content
) {
    /**
     * 将非 JSON 的原始文本降级封装为 WsInboundMessage。
     *
     * @param rawText 原始文本
     * @return type=MESSAGE、content=rawText 的入站消息
     */
    public static WsInboundMessage ofPlainText(String rawText) {
        return new WsInboundMessage(WsMessageType.MESSAGE.name(), null, rawText);
    }

    /**
     * 判断当前消息是否为指定类型（大小写不敏感）。
     *
     * @param messageType 目标类型
     * @return true 表示类型匹配
     */
    public boolean isType(WsMessageType messageType) {
        return messageType.name().equalsIgnoreCase(type);
    }
}
