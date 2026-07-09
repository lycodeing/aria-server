package com.aria.conversation.infrastructure.websocket.message;

/**
 * 座席被踢出信令（服务端 → 座席端，KICK 模式新端登录时推送给旧端）。
 *
 * <p>前端收到此信令后不触发重连，展示提示弹窗。
 * 仅含 {@code type} 字段，无需 sessionId（连接级别信令）。
 *
 * @param type 固定为 {@link WsMessageType#KICKED_OUT}
 */
public record WsKickedOutMessage(WsMessageType type) {

    /** 单例实例（不可变，可安全复用） */
    public static final WsKickedOutMessage INSTANCE =
            new WsKickedOutMessage(WsMessageType.KICKED_OUT);
}
