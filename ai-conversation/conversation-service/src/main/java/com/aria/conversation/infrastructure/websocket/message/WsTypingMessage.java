package com.aria.conversation.infrastructure.websocket.message;

/**
 * 输入中信令（ephemeral，服务端 → 访客端 / 服务端 → 座席端）。
 *
 * <p>TYPING 信令不写历史，允许丢失，用于实时展示对方"正在输入"状态。
 *
 * @param type      固定为 {@link WsMessageType#TYPING}
 * @param sessionId 会话 ID
 * @param timestamp 时间戳（epoch seconds）
 */
public record WsTypingMessage(
        WsMessageType type,
        String sessionId,
        long timestamp
) {
    /**
     * 创建 TYPING 信令。
     *
     * @param sessionId 会话 ID
     * @param timestamp 时间戳（epoch seconds）
     * @return TYPING 信令实例
     */
    public static WsTypingMessage of(String sessionId, long timestamp) {
        return new WsTypingMessage(WsMessageType.TYPING, sessionId, timestamp);
    }
}
