package com.aria.conversation.infrastructure.persistence.entity;

import com.aria.conversation.domain.SessionStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话生命周期实体（对应 cs_conversation.cs_conversation 表）。
 *
 * <p>状态字段使用 {@link SessionStatus} 枚举，MyBatis-Plus 通过 {@code @EnumValue}
 * 自动完成枚举与 DB VARCHAR 列的映射，无需手动调用 {@code .name()}。
 *
 * <p>不通过 setter 直接更新状态，业务状态变更通过 ConversationPersistRepository 的
 * 专用方法完成，保证状态机语义。
 */
@Data
@TableName(schema = "cs_conversation", value = "cs_conversation")
public class ConversationEntity {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话唯一标识（与 Redis chat:session:{id} 对应） */
    private String sessionId;

    /** 访客名称 */
    private String visitorName;

    /** 转接人工的原因描述 */
    private String transferReason;

    /** 问题分类标签（投诉/退款/咨询等） */
    private String tag;

    /**
     * 接入座席 ID（WAITING 时为 null，ACTIVE 后填入座席 ID）。
     * 转交会话时更新为新座席 ID。
     */
    private String agentId;

    /**
     * 会话状态枚举（WAITING / ACTIVE / CLOSED）。
     * MyBatis-Plus 自动将枚举的 {@code @EnumValue} 字段值映射到 DB VARCHAR 列。
     */
    private SessionStatus status;

    /** 会话开始时间（入队时间） */
    private OffsetDateTime startedAt;

    /**
     * 座席接入时间（WAITING → ACTIVE 转换时刻）。
     * 用于计算等待时长：accepted_at - started_at。
     * WAITING / AI_CHAT 状态下为 NULL。
     */
    private OffsetDateTime acceptedAt;

    /**
     * 座席首条回复时间（role=agent 的第一条消息时间）。
     * 用于计算首次响应时长（FRT）：first_reply_at - accepted_at。
     * 由 ConversationMessageConsumer 在消费到首条 agent 消息时写入，仅写一次。
     */
    private OffsetDateTime firstReplyAt;

    /** 会话结束时间（NULL 表示进行中） */
    private OffsetDateTime endedAt;

    /**
     * 关闭发起方：agent=座席主动关闭, visitor=访客离开, system=系统超时/自动关闭。
     * CLOSED 时必填，其余状态为 NULL。
     */
    private String closedBy;

    /** 记录创建时间 */
    private OffsetDateTime createdAt;

    /** 记录最后更新时间（由数据库触发器自动维护） */
    private OffsetDateTime updatedAt;
}
