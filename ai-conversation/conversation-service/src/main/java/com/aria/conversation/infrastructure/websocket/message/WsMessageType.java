package com.aria.conversation.infrastructure.websocket.message;

/**
 * WebSocket 消息类型枚举。
 *
 * <p>序列化为 JSON 时输出枚举名称（大写），与前端协议约定保持一致。
 */
public enum WsMessageType {
    /**
     * 连接建立确认信令
     */
    CONNECTED,
    /**
     * 座席被踢出（新端登录，KICK 模式）
     */
    KICKED_OUT,
    /**
     * 聊天消息（访客 ↔ 座席）
     */
    MESSAGE,
    /**
     * 输入中信令（ephemeral，不写历史）
     */
    TYPING,
    /**
     * 错误信令
     */
    ERROR,
    /**
     * 客户端心跳信令（ephemeral，静默忽略，不写历史、不转发）
     */
    PING
}
