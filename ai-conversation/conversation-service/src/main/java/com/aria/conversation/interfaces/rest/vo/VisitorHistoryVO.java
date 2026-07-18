package com.aria.conversation.interfaces.rest.vo;

import java.time.OffsetDateTime;

/**
 * 访客历史会话 VO。
 *
 * <p>用于座席工作台「历史工单」抽屉，展示同一访客的历史对话列表。
 *
 * @param sessionId  会话唯一标识
 * @param tag        问题分类标签（咨询/投诉/退款等）
 * @param status     会话状态字符串（AI_CHAT / WAITING / ACTIVE / CLOSED）
 * @param startedAt  会话开始时间
 * @param endedAt    会话结束时间（进行中时为 null）
 * @param msgCount       该会话的消息总数
 * @param aiSummary      AI 生成的会话摘要（未生成时为 null）
 * @param transferReason 转人工原因（无则为 null），供座席侧历史工单展示
 */
public record VisitorHistoryVO(
        String sessionId,
        String tag,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        int msgCount,
        String aiSummary,
        String transferReason
) {}
