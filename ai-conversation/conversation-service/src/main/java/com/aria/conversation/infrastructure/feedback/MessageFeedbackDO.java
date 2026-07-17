package com.aria.conversation.infrastructure.feedback;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 访客消息反馈实体（对应 cs_conversation.cs_message_feedback 表）。
 *
 * <p>访客对单条 AI/座席消息的点赞或点踩：(session_id, seq) 全局唯一。
 * feedback 只允许 {@code up} / {@code down}，取消反馈由 Service 删除整行而非更新为 null，
 * 保证 DB CHECK 约束语义收敛。
 *
 * <p>不使用 role 字段：反馈总是针对某条具体消息，seq 已定位到唯一行，
 * 是否为 assistant/agent 由查询侧按需 JOIN {@code cs_conversation_message}。
 */
@Data
@TableName(schema = "cs_conversation", value = "cs_message_feedback")
public class MessageFeedbackDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联会话 ID（与 cs_conversation_message.session_id 一致）。 */
    private String sessionId;

    /** 关联的 cs_conversation_message.seq（非 NULL）。 */
    private Long seq;

    /** 反馈类型：up / down。 */
    private String feedback;

    /** 访客标识（可 null，匿名访客）。 */
    private String visitorId;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
