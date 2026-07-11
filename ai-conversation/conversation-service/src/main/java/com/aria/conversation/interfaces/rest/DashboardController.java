package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.DashboardAppService;
import com.aria.conversation.interfaces.rest.vo.AgentWorkloadItemVO;
import com.aria.conversation.interfaces.rest.vo.ConversationTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.DashboardOverviewVO;
import com.aria.conversation.interfaces.rest.vo.EfficiencyTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.RecentSessionVO;
import com.aria.conversation.interfaces.rest.vo.StatusDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.TagDistributionItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dashboard 统计接口。
 *
 * <p>为前端 dashboard 页面（analytics / workspace）提供会话、消息、用户等维度
 * 的聚合统计数据。所有接口均为只读 GET 请求，不修改任何业务数据。
 *
 * <pre>
 * GET /api/v1/dashboard/overview              → 概览指标（今日会话量/总会话量/用户数/消息数/效率均值）
 * GET /api/v1/dashboard/conversation-trends   → 会话趋势（按天，支持 rangeType/days 参数）
 * GET /api/v1/dashboard/message-trends        → 消息量趋势（按天，支持 rangeType/days 参数）
 * GET /api/v1/dashboard/efficiency-trends     → 效率趋势（按天，平均等待/处理/首次回复时长）
 * GET /api/v1/dashboard/status-distribution   → 会话状态分布
 * GET /api/v1/dashboard/tag-distribution      → 问题标签分布
 * GET /api/v1/dashboard/recent-sessions       → 最近会话列表
 * GET /api/v1/dashboard/agent-workload        → 座席工作量统计
 * </pre>
 *
 * @author aria
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardAppService dashboardAppService;

    /** 概览指标（analytics 页面顶部 4 张卡片） */
    @GetMapping("/overview")
    public R<DashboardOverviewVO> getOverview() {
        return R.ok(dashboardAppService.getOverview());
    }

    /** 会话趋势（analytics 页面折线图，支持时间范围） */
    @GetMapping("/conversation-trends")
    public R<List<ConversationTrendItemVO>> getConversationTrends(
            @RequestParam(name = "rangeType", defaultValue = "month") String rangeType,
            @RequestParam(name = "days",      required = false)        Integer days) {
        return R.ok(dashboardAppService.getConversationTrends(rangeType, days));
    }

    /** 消息量趋势（analytics 页面柱状图，支持时间范围） */
    @GetMapping("/message-trends")
    public R<List<ConversationTrendItemVO>> getMessageTrends(
            @RequestParam(name = "rangeType", defaultValue = "month") String rangeType,
            @RequestParam(name = "days",      required = false)        Integer days) {
        return R.ok(dashboardAppService.getMessageTrends(rangeType, days));
    }

    /** 效率趋势（analytics 页面折线图，按天聚合三项时效均值） */
    @GetMapping("/efficiency-trends")
    public R<List<EfficiencyTrendItemVO>> getEfficiencyTrends(
            @RequestParam(name = "rangeType", defaultValue = "month") String rangeType,
            @RequestParam(name = "days",      required = false)        Integer days) {
        return R.ok(dashboardAppService.getEfficiencyTrends(rangeType, days));
    }

    /** 会话状态分布（analytics 页面饼图） */
    @GetMapping("/status-distribution")
    public R<List<StatusDistributionItemVO>> getStatusDistribution() {
        return R.ok(dashboardAppService.getStatusDistribution());
    }

    /** 问题标签分布（analytics 页面玫瑰图） */
    @GetMapping("/tag-distribution")
    public R<List<TagDistributionItemVO>> getTagDistribution() {
        return R.ok(dashboardAppService.getTagDistribution());
    }

    /** 最近会话列表（workspace 页面最近动态） */
    @GetMapping("/recent-sessions")
    public R<List<RecentSessionVO>> getRecentSessions(
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return R.ok(dashboardAppService.getRecentSessions(limit));
    }

    /** 座席工作量统计（workspace 页面项目卡片） */
    @GetMapping("/agent-workload")
    public R<List<AgentWorkloadItemVO>> getAgentWorkload() {
        return R.ok(dashboardAppService.getAgentWorkload());
    }
}
