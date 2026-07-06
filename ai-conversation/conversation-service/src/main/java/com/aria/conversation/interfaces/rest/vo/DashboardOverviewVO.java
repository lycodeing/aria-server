package com.aria.conversation.interfaces.rest.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dashboard 概览指标 VO。
 *
 * <p>用于前端 analytics 页面顶部的 4 张概览卡片展示。
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
}
