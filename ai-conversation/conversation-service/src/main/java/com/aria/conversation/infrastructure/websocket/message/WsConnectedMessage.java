package com.aria.conversation.infrastructure.websocket.message;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 连接建立确认信令（服务端 → 客户端）。
 *
 * <p>座席端：含 {@code type}、{@code podId}。
 * 访客端：含 {@code type}、{@code sessionId}、{@code role}、{@code podId}。
 * {@link JsonInclude#NON_NULL} 确保 null 字段不序列化。
 *
 * @param type      固定为 {@link WsMessageType#CONNECTED}
 * @param sessionId 会话 ID（访客端才有，座席端为 null）
 * @param role      角色（访客端为 "user"，座席端为 null）
 * @param podId     当前连接所在的 Pod 标识，用于调试和多 Pod 验证
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WsConnectedMessage(
        WsMessageType type,
        String sessionId,
        String role,
        String podId
) {
    /**
     * 座席端 CONNECTED 信令。
     *
     * @param podId 当前 Pod 标识
     */
    public static WsConnectedMessage forAgent(String podId) {
        return new WsConnectedMessage(WsMessageType.CONNECTED, null, null, podId);
    }

    /**
     * 访客端 CONNECTED 信令（含 sessionId、role、podId）。
     *
     * @param sessionId 会话 ID
     * @param podId     当前 Pod 标识
     */
    public static WsConnectedMessage forVisitor(String sessionId, String podId) {
        return new WsConnectedMessage(WsMessageType.CONNECTED, sessionId, "user", podId);
    }
}
