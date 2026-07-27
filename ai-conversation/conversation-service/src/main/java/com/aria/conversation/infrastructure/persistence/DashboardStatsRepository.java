package com.aria.conversation.infrastructure.persistence;

import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMapper;
import com.aria.conversation.infrastructure.persistence.mapper.DashboardStatsMapper;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import com.aria.conversation.interfaces.rest.vo.AgentWorkloadItemVO;
import com.aria.conversation.interfaces.rest.vo.ComplexityDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.ConversationTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.CsatByAgentItemVO;
import com.aria.conversation.interfaces.rest.vo.CsatDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.CsatOverviewVO;
import com.aria.conversation.interfaces.rest.vo.CsatTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.EfficiencyTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.RecentSessionVO;
import com.aria.conversation.interfaces.rest.vo.StatusDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.TagDistributionItemVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Dashboard 统计查询 Repository。
 *
 * <p>屏蔽底层 Mapper 的持久化细节，向 Application 层提供语义化统计接口。
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
    private final SlaBreachMapper      slaBreachMapper;
    private final ConversationMapper   conversationMapper;

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
    public long countByStatus(SessionStatus status) {
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
    public long countMessagesByRole(MessageRole role) {
        return statsMapper.countMessagesByRole(role);
    }

    // ---- SLA 统计 ----

    /**
     * 统计今日发生正式违规（stage=BREACH）的记录数。
     *
     * @param todayStart 今日零点（Asia/Shanghai 偏移时间）
     */
    public long countSlaBreachesToday(OffsetDateTime todayStart) {
        return slaBreachMapper.selectCount(
                Wrappers.<SlaBreachEntity>lambdaQuery()
                        .eq(SlaBreachEntity::getStage, BreachStage.BREACH)
                        .ge(SlaBreachEntity::getBreachAt, todayStart));
    }

    /**
     * 统计今日发生违规的不重复会话数。
     *
     * @param todayStart 今日零点（Asia/Shanghai 偏移时间）
     */
    public long countDistinctBreachedSessionsToday(OffsetDateTime todayStart) {
        return slaBreachMapper.countDistinctBreachedSessionsToday(todayStart);
    }

    /**
     * 统计今日已接入的人工会话数（accepted_at IS NOT NULL）。
     *
     * @param todayStart 今日零点（Asia/Shanghai 偏移时间）
     */
    public long countAgentSessionsToday(OffsetDateTime todayStart) {
        return conversationMapper.selectCount(
                Wrappers.<ConversationEntity>lambdaQuery()
                        .ge(ConversationEntity::getStartedAt, todayStart)
                        .isNotNull(ConversationEntity::getAcceptedAt));
    }

    // ---- 时长类指标 ----

    /** 平均等待时长（秒）：从入队到座席接入 */
    public long avgWaitSeconds() {
        return statsMapper.avgWaitSeconds();
    }

    /** 平均处理时长（秒）：从座席接入到会话结束 */
    public long avgHandleSeconds() {
        return statsMapper.avgHandleSeconds();
    }

    /** 平均首次响应时长（秒）：从座席接入到首条座席回复 */
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

    public List<ConversationTrendItemVO> getConversationTrendsByRange(LocalDate startDate, LocalDate endDate) {
        return statsMapper.getConversationTrendsByRange(startDate, endDate);
    }

    public List<ConversationTrendItemVO> getMessageTrendsByRange(LocalDate startDate, LocalDate endDate) {
        return statsMapper.getMessageTrendsByRange(startDate, endDate);
    }

    public List<EfficiencyTrendItemVO> getEfficiencyTrends(LocalDate startDate, LocalDate endDate) {
        return statsMapper.getEfficiencyTrends(startDate, endDate);
    }

    // ---- CSAT 指标 ----

    public double csatAvgScore()      { return statsMapper.csatAvgScore(); }
    public double csatResponseRate()  { return statsMapper.csatResponseRate(); }
    public long   csatRatedCount()    { return statsMapper.csatRatedCount(); }

    public List<CsatTrendItemVO> getCsatTrend(LocalDate s, LocalDate e) {
        return statsMapper.getCsatTrend(s, e);
    }

    public List<CsatDistributionItemVO> getCsatDistribution() {
        return statsMapper.getCsatDistribution();
    }

    public List<CsatByAgentItemVO> getCsatByAgent(int limit, int offset) {
        return statsMapper.getCsatByAgent(limit, offset);
    }

    public CsatOverviewVO getCsatOverview(LocalDate startDate, LocalDate endDate) {
        return statsMapper.getCsatOverview(startDate, endDate);
    }
}
