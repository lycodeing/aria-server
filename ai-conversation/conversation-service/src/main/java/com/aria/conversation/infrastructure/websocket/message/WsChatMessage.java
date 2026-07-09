package com.aria.conversation.infrastructure.websocket.message;

/**
 * 聊天消息（双向，服务端 → 访客端 / 服务端 → 座席端）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code role}：消息来源角色，{@code "user"}（访客）或 {@code "agent"}（座席）</li>
 *   <li>{@code seq}：消息序号，由 {@link com.aria.conversation.infrastructure.repository.ConversationHistoryRepository} 分配，
 *       支持客户端断线重连后通过 {@code sinceSeq} 增量同步</li>
 *   <li>{@code timestamp}：Unix 时间戳（秒级）</li>
 * </ul>
 *
 * @param type      固定为 {@link WsMessageType#MESSAGE}
 * @param sessionId 会话 ID
 * @param role      消息来源角色（"user" 或 "agent"）
 * @param content   消息文本内容
 * @param seq       消息序号
 * @param timestamp 消息时间戳（epoch seconds）
 */
public record WsChatMessage(
        WsMessageType type,
        String sessionId,
        String role,
        String content,
        long seq,
        long timestamp
) {
    /**
     * 构建访客发送的消息（推送给座席）。
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     * @param seq       消息序号
     * @param timestamp 时间戳（epoch seconds）
     * @return 角色为 "user" 的聊天消息
     */
    public static WsChatMessage fromVisitor(String sessionId, String content, long seq, long timestamp) {
        return new WsChatMessage(WsMessageType.MESSAGE, sessionId, "user", content, seq, timestamp);
    }

    /**
     * 构建座席发送的消息（推送给访客）。
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     * @param seq       消息序号
     * @param timestamp 时间戳（epoch seconds）
     * @return 角色为 "agent" 的聊天消息
     */
    public static WsChatMessage fromAgent(String sessionId, String content, long seq, long timestamp) {
        return new WsChatMessage(WsMessageType.MESSAGE, sessionId, "agent", content, seq, timestamp);
    }
}
