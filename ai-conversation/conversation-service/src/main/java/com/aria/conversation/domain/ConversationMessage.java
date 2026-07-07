package com.aria.conversation.domain;

import java.util.List;

/**
 * 对话消息值对象（不可变）。
 *
 * <p>在三个上下文中流通：
 * <ul>
 *   <li>Redis 热数据：JSON-per-slot 存入 List（每条消息一个 JSON 字符串）；兼容旧 3/4 元组格式</li>
 *   <li>AI prompt：LangChain4j {@code ChatMemoryStore} 反向重建为 UserMessage / AiMessage / ToolExecutionResultMessage</li>
 *   <li>REST API：原样序列化为 JSON 返回前端</li>
 * </ul>
 *
 * @param role          消息角色（user / assistant / agent / tool / system）
 * @param content       消息正文；tool_call-only assistant 消息允许为 null（或空串）
 * @param seq           会话内单调递增序号，用于断线重连增量同步；0 表示未分配（脏数据）
 * @param timestamp     消息写入时间戳（毫秒），旧数据可能为 null
 * @param toolRequestId LangChain4j ToolExecutionRequest ID，role=tool 时填充；nullable
 * @param toolName      工具名称，role=tool 时填充；nullable
 * @param toolCalls     assistant 触发的 tool_calls 请求列表，role=assistant 且有工具调用时非空；nullable
 */
public record ConversationMessage(
        String role,
        String content,
        long seq,
        Long timestamp,
        String toolRequestId,
        String toolName,
        List<ToolCall> toolCalls
) {

    /**
     * assistant 消息里承载的 tool_calls 单元。
     *
     * <p>与 LangChain4j {@code ToolExecutionRequest} 一一对应，用于跨请求恢复
     * "AI 请求工具" 这一中间态。
     *
     * @param id        请求 ID（工具结果消息通过该 ID 配对）
     * @param name      工具名称
     * @param arguments 参数 JSON 字符串（LangChain4j 原样传递）
     */
    public record ToolCall(String id, String name, String arguments) {
    }

    /**
     * 不含时间戳的便捷工厂方法，用于从 DB 数据或测试构造消息。
     */
    public static ConversationMessage of(String role, String content, long seq) {
        return new ConversationMessage(role, content, seq, null, null, null, null);
    }

    /**
     * 四参数向后兼容构造器，供 Redis 反序列化（旧格式四元组）使用。
     * toolRequestId / toolName / toolCalls 默认为 null。
     */
    public ConversationMessage(String role, String content, long seq, Long timestamp) {
        this(role, content, seq, timestamp, null, null, null);
    }

    /**
     * 六参数兼容构造器，保持既有调用点（{@code SessionChatMemoryStore} 早期版本）不变。
     * toolCalls 默认为 null。
     */
    public ConversationMessage(String role, String content, long seq, Long timestamp,
                               String toolRequestId, String toolName) {
        this(role, content, seq, timestamp, toolRequestId, toolName, null);
    }
}
