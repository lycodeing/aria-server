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
 * @param role          消息角色（user / assistant / agent / tool）
 * @param content       消息正文
 * @param seq           会话内单调递增序号，用于断线重连增量同步；0 表示未分配（脏数据）
 * @param timestamp     消息写入时间戳（毫秒），旧数据可能为 null
 * @param toolRequestId LangChain4j ToolExecutionRequest ID，role=tool 时填充；nullable
 * @param toolName      工具名称，role=tool 时填充；nullable
 */
public record ConversationMessage(
        String role,
        String content,
        long seq,
        Long timestamp,
        String toolRequestId,
        String toolName
) {

    /**
     * 不含时间戳的便捷工厂方法，用于从 DB 数据或测试构造消息。
     */
    public static ConversationMessage of(String role, String content, long seq) {
        return new ConversationMessage(role, content, seq, null, null, null);
    }

    /**
     * 四参数向后兼容构造器，供 Redis 反序列化（旧格式四元组）使用。
     * toolRequestId 和 toolName 默认为 null。
     */
    public ConversationMessage(String role, String content, long seq, Long timestamp) {
        this(role, content, seq, timestamp, null, null);
    }
}
