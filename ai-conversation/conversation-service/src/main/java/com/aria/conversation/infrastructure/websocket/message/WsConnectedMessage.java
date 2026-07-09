package com.aria.conversation.infrastructure.websocket.message;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 连接建立确认信令（服务端 → 客户端）。
 *
 * <p>座席端：仅含 {@code type} 字段。
 * 访客端：含 {@code type}、{@code sessionId}、{@code role} 字段。
 * {@link JsonInclude#NON_NULL} 确保 null 字段不序列化，一个类覆盖两种场景。
 *
 * @param type      固定为 {@link WsMessageType#CONNECTED}
 * @param sessionId 会话 ID（访客端才有，座席端为 null）
 * @param role      角色（访客端为 "user"，座席端为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WsConnectedMessage(
        WsMessageType type,
        String sessionId,
        String role
) {
    /** 座席端 CONNECTED 信令（仅 type 字段） */
    public static WsConnectedMessage forAgent() {
        return new WsConnectedMessage(WsMessageType.CONNECTED, null, null);
    }

    /**
     * 访客端 CONNECTED 信令（含 sessionId 和 role）。
     *
     * @param sessionId 会话 ID
     * @return 访客 CONNECTED 信令
     */
    public static WsConnectedMessage forVisitor(String sessionId) {
        return new WsConnectedMessage(WsMessageType.CONNECTED, sessionId, "user");
    }
}
