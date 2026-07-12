package com.aria.conversation.infrastructure.persistence;

import com.aria.conversation.infrastructure.persistence.mapper.DashboardStatsMapper;
import com.aria.conversation.interfaces.rest.vo.AgentWorkloadItemVO;
import com.aria.conversation.interfaces.rest.vo.ComplexityDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.ConversationTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.EfficiencyTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.RecentSessionVO;
import com.aria.conversation.interfaces.rest.vo.StatusDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.TagDistributionItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard 统计查询 Repository。
 *
 * <p>屏蔽 {@link DashboardStatsMapper} 的持久化细节，向 Application 层提供语义化统计接口。
 * 遵循 DDD 分层规范：Application Service 不直接依赖 Mapper（infrastructure 实现细节），
 * 而是通过本 Repository 获取统计数据。
 *
 * <p>本类为只读 Repository，所有方法均不涉及写操作。
 *
 * @author aria
 */
@Repository
@RequiredArgsConstructor
public class DashboardStatsRepository {

    private final DashboardStatsMapper statsMapper;

    // ---- 概览指标 ----

    /** 今日会话量 */
    public long countTodayConversations() {
        return statsMapper.countTodayConversations();
    }

    /** 总会话量 */
    public long countTotalConversations() {
        return statsMapper.countTotalConversations();
    }

    /** 按状态统计会话数 */
    public long countByStatus(String status) {
        return statsMapper.countByStatus(status);
    }

    /** 总用户数（cs_auth.sys_user 未删除） */
    public long countTotalUsers() {
        return statsMapper.countTotalUsers();
    }

    /** 总消息数 */
    public long countTotalMessages() {
        return statsMapper.countTotalMessages();
    }

    /** 按角色统计消息数 */
    public long countMessagesByRole(String role) {
        return statsMapper.countMessagesByRole(role);
    }

    // ---- 时长类指标 ----

    /**
     * 平均等待时长（秒）：从入队到座席接入。
     * 仅统计已接入的会话（accepted_at IS NOT NULL）。
     */
    public long avgWaitSeconds() {
        return statsMapper.avgWaitSeconds();
    }

    /**
     * 平均处理时长（秒）：从座席接入到会话结束。
     * 仅统计已关闭的人工会话（ended_at 和 accepted_at 均不为 NULL）。
     */
    public long avgHandleSeconds() {
        return statsMapper.avgHandleSeconds();
    }

    /**
     * 平均首次响应时长（秒）：从座席接入到首条座席回复。
     * 仅统计已有首条回复的会话（first_reply_at 和 accepted_at 均不为 NULL）。
     */
    public long avgFirstReplySeconds() {
        return statsMapper.avgFirstReplySeconds();
    }

    // ---- 图表数据 ----

    /** 按月会话趋势（区分人工/AI，近 12 个月） */
    public List<ConversationTrendItemVO> getMonthlyTrends() {
        return statsMapper.getMonthlyTrends();
    }

    /** 按月消息量趋势（近 12 个月） */
    public List<ConversationTrendItemVO> getMonthlyMessageTrends() {
        return statsMapper.getMonthlyMessageTrends();
    }

    /** 会话状态分布 */
    public List<StatusDistributionItemVO> getStatusDistribution() {
        return statsMapper.getStatusDistribution();
    }

    /** 问题标签分布 */
    public List<TagDistributionItemVO> getTagDistribution() {
        return statsMapper.getTagDistribution();
    }

    // ---- Workspace 页面数据 ----

    /**
     * 最近会话列表（含消息数）。
     *
     * @param limit 返回条数，1~50
     */
    public List<RecentSessionVO> getRecentSessions(int limit) {
        return statsMapper.getRecentSessions(limit);
    }

    /** 座席工作量统计 */
    public List<AgentWorkloadItemVO> getAgentWorkload() {
        return statsMapper.getAgentWorkload();
    }

    /**
     * 会话复杂度分布（SIMPLE / MEDIUM / COMPLEX）。
     * 阈值从 cs_auth.system_config 动态读取，兜底默认值：simpleMax=5，mediumMax=15。
     */
    public List<ComplexityDistributionItemVO> getComplexityDistribution() {
        int simpleMax = Integer.parseInt(
                statsMapper.getConfigValue("complexity.simpleMaxMessages", "5"));
        int mediumMax = Integer.parseInt(
                statsMapper.getConfigValue("complexity.mediumMaxMessages", "15"));
        return statsMapper.getComplexityDistribution(simpleMax, mediumMax);
    }

    // ---- 按时间范围聚合（按天） ----

    /**
     * 会话趋势（按天聚合，支持时间范围）。
     *
     * @param startDate 开始日期（含）
     * @param endDate   结束日期（含）
     */
    public List<ConversationTrendItemVO> getConversationTrendsByRange(LocalDate startDate, LocalDate endDate) {
        return statsMapper.getConversationTrendsByRange(startDate, endDate);
    }

    /**
     * 消息量趋势（按天聚合，支持时间范围）。
     *
     * @param startDate 开始日期（含）
     * @param endDate   结束日期（含）
     */
    public List<ConversationTrendItemVO> getMessageTrendsByRange(LocalDate startDate, LocalDate endDate) {
        return statsMapper.getMessageTrendsByRange(startDate, endDate);
    }

    /**
     * 效率趋势（按天聚合，支持时间范围）。
     * 返回每天的平均等待/处理/首次回复时长（秒）。
     *
     * @param startDate 开始日期（含）
     * @param endDate   结束日期（含）
     */
    public List<EfficiencyTrendItemVO> getEfficiencyTrends(LocalDate startDate, LocalDate endDate) {
        return statsMapper.getEfficiencyTrends(startDate, endDate);
    }
}
