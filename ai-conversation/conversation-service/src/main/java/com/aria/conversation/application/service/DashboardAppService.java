package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.persistence.mapper.DashboardStatsMapper;
import com.aria.conversation.interfaces.rest.vo.AgentWorkloadItemVO;
import com.aria.conversation.interfaces.rest.vo.ConversationTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.DashboardOverviewVO;
import com.aria.conversation.interfaces.rest.vo.RecentSessionVO;
import com.aria.conversation.interfaces.rest.vo.StatusDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.TagDistributionItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dashboard 统计应用服务。
 *
 * <p>负责组合 {@link DashboardStatsMapper} 的多次聚合查询，
 * 为前端 dashboard 页面提供统一的统计数据出口。
 *
 * <p>所有方法均为只读查询，不涉及事务。
 *
 * @author aria
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardAppService {

    private final DashboardStatsMapper statsMapper;

    /**
     * 获取概览指标（analytics 页面顶部卡片）。
     *
     * <p>组合 8 次聚合查询，一次性返回所有概览数据。
     * 各查询独立执行，单次查询失败不影响其他指标（返回 0）。
     *
     * @return 概览指标 VO
     */
    public DashboardOverviewVO getOverview() {
        return DashboardOverviewVO.builder()
                .todayConversationCount(safeCount(statsMapper::countTodayConversations))
                .totalConversationCount(safeCount(statsMapper::countTotalConversations))
                .activeConversationCount(safeCount(() -> statsMapper.countByStatus("ACTIVE")))
                .waitingConversationCount(safeCount(() -> statsMapper.countByStatus("WAITING")))
                .totalUserCount(safeCount(statsMapper::countTotalUsers))
                .totalMessageCount(safeCount(statsMapper::countTotalMessages))
                .aiMessageCount(safeCount(() -> statsMapper.countMessagesByRole("assistant")))
                .agentMessageCount(safeCount(() -> statsMapper.countMessagesByRole("agent")))
                .build();
    }

    /**
     * 会话趋势（analytics 页面折线图）。
     *
     * @return 按月聚合的趋势数据列表
     */
    public List<ConversationTrendItemVO> getConversationTrends() {
        return statsMapper.getMonthlyTrends();
    }

    /**
     * 消息量趋势（analytics 页面柱状图）。
     *
     * @return 按月聚合的消息量数据列表
     */
    public List<ConversationTrendItemVO> getMessageTrends() {
        return statsMapper.getMonthlyMessageTrends();
    }

    /**
     * 会话状态分布（analytics 页面饼图）。
     *
     * @return 状态分布数据列表
     */
    public List<StatusDistributionItemVO> getStatusDistribution() {
        return statsMapper.getStatusDistribution();
    }

    /**
     * 问题标签分布（analytics 页面玫瑰图）。
     *
     * @return 标签分布数据列表
     */
    public List<TagDistributionItemVO> getTagDistribution() {
        return statsMapper.getTagDistribution();
    }

    /**
     * 最近会话列表（workspace 页面最近动态）。
     *
     * @param limit 返回条数，默认 10
     * @return 最近会话列表
     */
    public List<RecentSessionVO> getRecentSessions(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return statsMapper.getRecentSessions(safeLimit);
    }

    /**
     * 座席工作量统计（workspace 页面项目卡片）。
     *
     * @return 座席工作量列表
     */
    public List<AgentWorkloadItemVO> getAgentWorkload() {
        return statsMapper.getAgentWorkload();
    }

    // ============================================================
    // 内部工具方法
    // ============================================================

    /**
     * 安全执行计数查询，异常时返回 0 并记录日志。
     * 避免单个指标查询失败导致整个概览接口 500。
     */
    private long safeCount(java.util.concurrent.Supplier<Long> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Dashboard 统计查询失败，返回 0: {}", e.getMessage());
            return 0L;
        }
    }
}
