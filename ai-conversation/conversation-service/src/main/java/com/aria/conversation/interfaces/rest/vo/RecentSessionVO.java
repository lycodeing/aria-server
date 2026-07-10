package com.aria.conversation.interfaces.rest.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 最近会话 VO（用于 workspace 页面最近动态列表）。
 *
 * <p>展示最近 N 条会话的摘要信息，按开始时间降序排列。
 *
 * @author aria
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentSessionVO {

    /** 会话唯一标识 */
    private String sessionId;

    /** 访客名称 */
    private String visitorName;

    /** 问题分类标签 */
    private String tag;

    /** 转接人工原因 */
    private String transferReason;

    /** 会话状态（WAITING / ACTIVE / CLOSED） */
    private String status;

    /** 接入座席 ID */
    private String agentId;

    /** 会话开始时间 */
    private OffsetDateTime startedAt;

    /** 座席接入时间（WAITING→ACTIVE，可为 NULL） */
    private OffsetDateTime acceptedAt;

    /** 会话结束时间 */
    private OffsetDateTime endedAt;

    /** 关闭发起方（agent / visitor / system，进行中为 NULL） */
    private String closedBy;

    /** 该会话的消息总数 */
    private long messageCount;
}
