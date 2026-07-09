package com.aria.conversation.infrastructure.websocket.message;

/**
 * 错误信令（服务端 → 客户端）。
 *
 * <p>用于通知客户端发生了可感知的错误（如消息超长），通常随后跟随连接关闭。
 *
 * @param type    固定为 {@link WsMessageType#ERROR}
 * @param message 错误描述（面向客户端展示）
 */
public record WsErrorMessage(
        WsMessageType type,
        String message
) {
    /**
     * 创建错误信令。
     *
     * @param message 错误描述
     * @return 错误信令实例
     */
    public static WsErrorMessage of(String message) {
        return new WsErrorMessage(WsMessageType.ERROR, message);
    }
}
