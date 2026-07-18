package com.aria.conversation.application.dto;

import java.time.OffsetDateTime;

/**
 * 访客历史会话应用层 DTO。
 *
 * <p>由 {@link com.aria.conversation.application.service.VisitorHistoryService} 生成并返回，
 * Controller 层负责将其映射为对外的 {@code VisitorHistoryVO}，
 * 保持 application 层对 interfaces 层的单向依赖。
 *
 * @param sessionId  会话唯一标识
 * @param tag        问题分类标签
 * @param status     会话状态字符串
 * @param startedAt  会话开始时间
 * @param endedAt    会话结束时间（进行中时为 null）
 * @param msgCount       消息总数
 * @param aiSummary      AI 生成的摘要（未生成时为 null）
 * @param transferReason 转人工原因（无则为 null）
 */
public record VisitorHistoryDTO(
        String sessionId,
        String tag,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        int msgCount,
        String aiSummary,
        String transferReason
) {}
