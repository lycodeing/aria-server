package com.aidevplatform.conversation.infrastructure.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话 RabbitMQ 消息 DTO（持久化链路）。
 *
 * <p>发布到 {@code cs.conversation} Direct Exchange，由
 * {@link ConversationMessageConsumer} 消费后写入 PostgreSQL。
 *
 * <p>消息类型（type 字段）：
 * <ul>
 *   <li>{@code MESSAGE}       — 单条对话消息（用户/AI/座席），由 ConversationHistoryRepository.append() 发布</li>
 *   <li>{@code SESSION_START} — 会话创建，由 SessionQueueService.enqueue() 发布</li>
 *   <li>{@code SESSION_ACCEPT}— 座席接入，由 SessionQueueService.accept() 发布</li>
 *   <li>{@code SESSION_END}   — 会话结束，由 SessionQueueService.close() 发布</li>
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
 *
 * <p>注意：该类仅用于静态常量（{@code FIELD_*}）和 {@link Type} 枚举。
 * 消息体实际以 {@code Map<String, Object>} 形式发布，Lombok 注解保留供潜在 DTO 用途。
 */
// TODO: 若确认不需要将此类作为 DTO 实例化，可移除 @Data/@Builder/@NoArgsConstructor/@AllArgsConstructor
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationStreamEvent {

    // ---- Stream Map 字段名常量，避免魔法字符串 ----
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_SESSION_ID = "sessionId";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_VISITOR_NAME = "visitorName";
    public static final String FIELD_TAG = "tag";
    public static final String FIELD_TRANSFER_REASON = "transferReason";
    public static final String FIELD_TIMESTAMP = "timestamp";
    /**
     * 消息类型
     */
    private Type type;
    /**
     * 会话唯一标识
     */
    private String sessionId;
    /**
     * 消息角色（user / assistant / agent），SESSION_START/END 时为 null
     */
    private String role;
    /**
     * 消息内容，SESSION_START/END 时为 null
     */
    private String content;
    /**
     * 访客名称，仅 SESSION_START 有效
     */
    private String visitorName;
    /**
     * 问题分类标签，仅 SESSION_START 有效
     */
    private String tag;
    /**
     * 转接原因，仅 SESSION_START 有效
     */
    private String transferReason;
    /**
     * 消息时间戳（epoch seconds）
     */
    private long timestamp;
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
        SESSION_END
    }
}
