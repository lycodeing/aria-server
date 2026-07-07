package com.aria.conversation.infrastructure.mq;

/**
 * 对话 RabbitMQ 消息常量持有类（持久化链路）。
 *
 * <p>发布到 {@code cs.conversation} Direct Exchange，由
 * {@link ConversationMessageConsumer} 消费后写入 PostgreSQL。
 *
 * <p>消息体以 {@code Map<String, Object>} 形式发布，本类仅提供：
 * <ul>
 *   <li>{@code FIELD_*} 常量 — Map 字段名，避免魔法字符串散落各处</li>
 *   <li>{@link Type} 枚举 — 消息类型标识</li>
 * </ul>
 *
 * <p>消息类型（type 字段）：
 * <ul>
 *   <li>{@code MESSAGE}        — 单条对话消息（用户/AI/座席），由 ConversationHistoryRepository.append() 发布</li>
 *   <li>{@code SESSION_START}  — 会话创建，由 SessionQueueService.enqueue() 发布</li>
 *   <li>{@code SESSION_ACCEPT} — 座席接入，由 SessionQueueService.accept() 发布</li>
 *   <li>{@code SESSION_END}    — 会话结束，由 SessionQueueService.close() 发布</li>
 * </ul>
 *
 * <p>消息 Map 字段（Jackson 序列化为 JSON）：
 * <pre>
 *   type           消息类型
 *   sessionId      会话唯一标识
 *   role           消息角色（user / assistant / agent），仅 MESSAGE 有效
 *   content        消息内容，仅 MESSAGE 有效
 *   visitorName    访客名称，仅 SESSION_START 有效
 *   tag            问题分类标签，仅 SESSION_START 有效
 *   transferReason 转接原因，仅 SESSION_START 有效
 *   timestamp      消息时间戳（epoch seconds）
 * </pre>
 */
public final class ConversationStreamEvent {

    /** 工具类，禁止实例化 */
    private ConversationStreamEvent() {}

    // ---- Stream Map 字段名常量，避免魔法字符串 ----
    public static final String FIELD_TYPE            = "type";
    public static final String FIELD_SESSION_ID      = "sessionId";
    public static final String FIELD_ROLE            = "role";
    public static final String FIELD_CONTENT         = "content";
    public static final String FIELD_VISITOR_NAME    = "visitorName";
    public static final String FIELD_TAG             = "tag";
    public static final String FIELD_TRANSFER_REASON = "transferReason";
    public static final String FIELD_TIMESTAMP       = "timestamp";
    /** session 内单调递增序号，仅 MESSAGE 类型有效，支持客户端 sinceSeq 增量同步 */
    public static final String FIELD_SEQ             = "seq";
    /** 座席接入或转交事件的座席 ID，对应 cs_conversation.agent_id 字段 */
    public static final String FIELD_AGENT_ID        = "agentId";
    /** 转交事件的源座席 ID（仅 SESSION_TRANSFER 有效） */
    public static final String FIELD_FROM_AGENT_ID   = "fromAgentId";
    /** 转交事件的目标座席 ID（仅 SESSION_TRANSFER 有效） */
    public static final String FIELD_TO_AGENT_ID     = "toAgentId";
    /** LangChain4j ToolExecutionRequest ID，仅 MESSAGE 且 role=tool 时有效 */
    public static final String FIELD_TOOL_REQUEST_ID = "toolRequestId";
    /** 工具名称，仅 MESSAGE 且 role=tool 时有效 */
    public static final String FIELD_TOOL_NAME       = "toolName";
    /** assistant 触发的 tool_calls JSON 数组（{@code [{id,name,arguments}, ...]}），仅 MESSAGE 且 role=assistant 有工具调用时有效 */
    public static final String FIELD_TOOL_CALLS      = "toolCalls";

    /**
     * 消息类型枚举
     */
    public enum Type {
        /** 单条对话消息 */
        MESSAGE,
        /** 会话创建（用户请求转人工） */
        SESSION_START,
        /** 座席接入会话（WAITING → ACTIVE） */
        SESSION_ACCEPT,
        /** 会话结束（座席主动关闭或断线） */
        SESSION_END,
        /** 会话转交（A 座席 → B 座席，状态仍为 ACTIVE） */
        SESSION_TRANSFER
    }
}
