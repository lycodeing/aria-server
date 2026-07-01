package com.aria.conversation.domain;

/**
 * 对话消息值对象（不可变）。
 *
 * <p>在三个上下文中流通：
 * <ul>
 *   <li>Redis 热数据：role/content/seq/timestamp 四元组，按位置序列化存入 List</li>
 *   <li>AI prompt：仅用 role/content，seq 和 timestamp 由 {@code toAiPrompt} 剥离</li>
 *   <li>REST API：原样序列化为 JSON 返回前端</li>
 * </ul>
 *
 * @param role      消息角色（user / assistant / agent）
 * @param content   消息正文
 * @param seq       会话内单调递增序号，用于断线重连增量同步；0 表示未分配（脏数据）
 * @param timestamp 消息写入时间戳（毫秒），旧数据可能为 null
 */
public record ConversationMessage(String role, String content, long seq, Long timestamp) {

    /**
     * 不含时间戳的便捷工厂方法，用于从 DB 数据或测试构造消息。
     */
    public static ConversationMessage of(String role, String content, long seq) {
        return new ConversationMessage(role, content, seq, null);
    }
}
