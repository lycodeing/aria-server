package com.aria.conversation.interfaces.rest.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dashboard 概览指标 VO。
 *
 * <p>用于前端 analytics 页面顶部的概览卡片展示。
 * 所有指标均为聚合统计值，由 DashboardAppService 一次性查询组装。
 *
 * @author aria
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardOverviewVO {

    /** 今日会话量 */
    private long todayConversationCount;

    /** 总会话量 */
    private long totalConversationCount;

    /** 当前进行中会话数（ACTIVE 状态） */
    private long activeConversationCount;

    /** 当前等待接入会话数（WAITING 状态） */
    private long waitingConversationCount;

    /** 总用户数（cs_auth.sys_user 未删除） */
    private long totalUserCount;

    /** 总消息数 */
    private long totalMessageCount;

    /** AI 回复消息数（role = assistant） */
    private long aiMessageCount;

    /** 人工座席回复消息数（role = agent） */
    private long agentMessageCount;

    /**
     * 平均等待时长（秒）。
     * 统计范围：accepted_at 和 started_at 均不为 NULL 的已接入会话。
     * 计算公式：AVG(accepted_at - started_at)。
     */
    private long avgWaitSeconds;

    /**
     * 平均处理时长（秒）。
     * 统计范围：ended_at 和 accepted_at 均不为 NULL 的已关闭人工会话。
     * 计算公式：AVG(ended_at - accepted_at)。
     */
    private long avgHandleSeconds;

    /**
     * 平均首次响应时长（秒）。
     * 统计范围：first_reply_at 和 accepted_at 均不为 NULL 的已接入会话。
     * 计算公式：AVG(first_reply_at - accepted_at)。
     */
    private long avgFirstReplySeconds;
}
