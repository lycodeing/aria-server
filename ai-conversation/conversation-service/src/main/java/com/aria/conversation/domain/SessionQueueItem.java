package com.aria.conversation.domain;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 会话队列项（领域对象）。
 *
 * <p>代表一个进入人工服务队列的访客会话，包含状态、等待时间、接入座席等核心信息。
 *
 * <p>⚠️ {@link JsonPropertyOrder} 固定序列化字段顺序：
 * {@link com.aria.conversation.infrastructure.repository.SessionQueueRepository} 的 CAS 操作
 * 依赖 Jackson 输出的字段顺序做字符串匹配，禁止调整 record 组件顺序，
 * 否则 MARKER_STATUS_WAITING / MARKER_AGENT_ID_TPL 匹配失效。
 *
 * @param sessionId      会话唯一标识
 * @param userName       访客名称
 * @param transferReason 转人工原因
 * @param tag            问题分类标签
 * @param waitSince      进入队列时间（epoch seconds）
 * @param status         当前状态（WAITING / ACTIVE / CLOSED）
 * @param agentId        接入座席 ID（WAITING 时为 null，ACTIVE 后填入）
 */
@JsonPropertyOrder({"sessionId", "userName", "transferReason", "tag", "waitSince", "status", "agentId"})
public record SessionQueueItem(
        String sessionId,
        String userName,
        String transferReason,
        String tag,
        long waitSince,
        SessionStatus status,
        String agentId
) {}
