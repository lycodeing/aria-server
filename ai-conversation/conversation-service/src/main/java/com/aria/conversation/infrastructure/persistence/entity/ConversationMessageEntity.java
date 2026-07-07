package com.aria.conversation.infrastructure.persistence.entity;

import com.aria.conversation.domain.MessageRole;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 对话消息明细实体（对应 cs_conversation.cs_conversation_message 表）。
 *
 * <p>角色字段使用 {@link MessageRole} 枚举，MyBatis-Plus 通过 {@code @EnumValue}
 * 自动完成枚举与 DB VARCHAR 列的映射。
 *
 * <p>消息时间由 MQ/Stream 消息的 timestamp 字段决定，保证与 Redis 历史顺序一致。
 */
@Data
@TableName(schema = "cs_conversation", value = "cs_conversation_message")
public class ConversationMessageEntity {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联会话 ID（冗余字段，避免查询历史时 JOIN cs_conversation）。
     * 与 ConversationEntity.sessionId 保持一致。
     */
    private String sessionId;

    /**
     * 消息角色枚举（user / assistant / agent）。
     * MyBatis-Plus 自动将枚举的 {@code @EnumValue} 字段值映射到 DB VARCHAR 列。
     *
     * @see MessageRole
     */
    private MessageRole role;

    /** 消息内容 */
    private String content;

    /**
     * session 内单调递增序号（由 ConversationHistoryRepository.nextSeq 生成）。
     * 支持客户端断线重连后通过 sinceSeq 增量拉取历史，避免每次重连全量。
     * 历史遗留消息允许为 null（迁移前未生成）。
     */
    private Long seq;

    /** 消息时间（从 MQ/Stream 事件的 timestamp 恢复，单位 epoch seconds） */
    private OffsetDateTime createdAt;

    /**
     * LangChain4j {@code ToolExecutionRequest} ID，role=tool 时填充。
     * 与 assistant 消息的 tool_calls[i].id 一一对应，用于跨会话恢复
     * "AI 请求工具 → 工具返回结果" 的多轮上下文。
     */
    private String toolRequestId;

    /** 工具名称，role=tool 时填充。 */
    private String toolName;

    /**
     * assistant 触发的 tool_calls JSON 数组：{@code [{"id":"...","name":"...","arguments":"..."}]}。
     * 仅 role=assistant 且模型返回 tool_calls 时非空。JSON 反序列化在应用层完成。
     */
    private String toolCallsJson;
}
