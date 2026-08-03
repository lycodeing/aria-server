package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.interfaces.rest.vo.AgentWorkloadItemVO;
import com.aria.conversation.interfaces.rest.vo.ComplexityDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.ConversationTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.CsatByAgentItemVO;
import com.aria.conversation.interfaces.rest.vo.CsatDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.CsatOverviewVO;
import com.aria.conversation.interfaces.rest.vo.CsatTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.DashboardOverviewVO;
import com.aria.conversation.interfaces.rest.vo.EfficiencyTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.RecentSessionVO;
import com.aria.conversation.interfaces.rest.vo.StatusDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.TagDistributionItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard 统计查询 Mapper。
 *
 * <p>专门用于 Dashboard 统计聚合查询，与 {@link ConversationMapper}（CRUD）分离，
 * 遵循单一职责原则。所有 SQL 定义在 DashboardStatsMapper.xml 中，
 * 因为涉及跨 schema 聚合统计、FILTER、EXTRACT 等无法通过 LambdaWrapper 表达的语法。
 *
 * <p>跨 schema 查询说明：
 * <ul>
 *   <li>{@code cs_conversation.*} — 会话/消息表（本服务 schema）</li>
 *   <li>{@code cs_auth.sys_user} — 用户表（同一数据库 ai_customerservice，不同 schema）</li>
 * </ul>
 *
 * @author aria
 */
@Mapper
public interface DashboardStatsMapper {

    // ============================================================
    // 概览指标
    // ============================================================

    /** 今日会话量（started_at 在今天） */
    long countTodayConversations();

    /** 总会话量 */
    long countTotalConversations();

    /** 按状态统计会话数 */
    long countByStatus(@Param("status") SessionStatus status);

    /** 总用户数（cs_auth.sys_user 未删除） */
    long countTotalUsers();

    /** 总消息数 */
    long countTotalMessages();

    /** 按角色统计消息数 */
    long countMessagesByRole(@Param("role") MessageRole role);

    // ============================================================
    // 图表数据
    // ============================================================

    /**
     * 会话趋势（按月聚合，区分人工/AI）。
     * tag != 'AI 对话' 为人工会话，tag = 'AI 对话' 为纯 AI 会话。
     * 返回最近 12 个月的数据。
     */
    List<ConversationTrendItemVO> getMonthlyTrends();

    /** 月消息量趋势（按月聚合，用于柱状图） */
    List<ConversationTrendItemVO> getMonthlyMessageTrends();

    /** 会话状态分布 */
    List<StatusDistributionItemVO> getStatusDistribution();

    /**
     * 问题标签分布。
     * tag 为 NULL 的归类为"未分类"。
     */
    List<TagDistributionItemVO> getTagDistribution();

    // ============================================================
    // workspace 页面数据
    // ============================================================

    /**
     * 最近会话列表（含消息数子查询）。
     *
     * @param limit 返回条数
     */
    List<RecentSessionVO> getRecentSessions(@Param("limit") int limit);

    /** 座席工作量统计（按 agent_id 聚合） */
    List<AgentWorkloadItemVO> getAgentWorkload();

    // ============================================================
    // 时长类指标（需要 accepted_at / first_reply_at）
    // ============================================================

    /**
     * 平均等待时长（秒）：从入队到座席接入。
     * 仅统计已接入的会话（accepted_at IS NOT NULL）。
     */
    long avgWaitSeconds();

    /**
     * 平均处理时长（秒）：从座席接入到会话结束。
     * 仅统计已关闭的人工会话（ended_at IS NOT NULL AND accepted_at IS NOT NULL）。
     */
    long avgHandleSeconds();

    /**
     * 平均首次响应时长（秒）：从座席接入到首条座席回复。
     * 仅统计已有首条回复的会话（first_reply_at IS NOT NULL AND accepted_at IS NOT NULL）。
     */
    long avgFirstReplySeconds();

    // ============================================================
    // 按时间范围聚合（按天，支持时间范围筛选）
    // ============================================================

    /**
     * 会话趋势（按天聚合，支持时间范围）。
     * 以 tag='AI 对话' 区分纯 AI 会话，其余均视为人工会话（含 WAITING 状态）。
     */
    List<ConversationTrendItemVO> getConversationTrendsByRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate);

    /**
     * 消息量趋势（按天聚合，支持时间范围）。
     * 区分 agent（人工）和 assistant（AI）两类角色。
     */
    List<ConversationTrendItemVO> getMessageTrendsByRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate);

    /**
     * 效率趋势（按天聚合，支持时间范围）。
     * 返回每天的平均等待/处理/首次回复时长（秒）。
     * 仅统计 accepted_at IS NOT NULL 的会话。
     */
    List<EfficiencyTrendItemVO> getEfficiencyTrends(
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate);

    /**
     * 从 cs_auth.system_config 读取单个配置值。
     * 跨 schema 查询，与本服务在同一 PostgreSQL 实例中。
     * 找不到或已禁用时返回 {@code defaultValue}。
     */
    String getConfigValue(@Param("key") String key, @Param("defaultValue") String defaultValue);

    /**
     * 会话复杂度分布。
     * 以每条会话的消息数作为复杂度度量，分三档：SIMPLE / MEDIUM / COMPLEX。
     * 阈值由调用方从 system_config 动态读取，避免硬编码。
     */
    List<ComplexityDistributionItemVO> getComplexityDistribution(
            @Param("simpleMax") int simpleMax,
            @Param("mediumMax") int mediumMax);

    // ============================================================
    // CSAT 指标
    // ============================================================

    /** 近 30 天平均 CSAT 分（仅 RATED 状态） */
    double csatAvgScore();

    /** 近 30 天评价响应率：RATED / (RATED + EXPIRED + SKIPPED) */
    double csatResponseRate();

    /** 近 30 天已评价总数 */
    long csatRatedCount();

    /** 按天聚合的 CSAT 均分趋势 */
    List<CsatTrendItemVO> getCsatTrend(@Param("startDate") LocalDate startDate,
                                        @Param("endDate")   LocalDate endDate);

    /** 1–5 星分布 */
    List<CsatDistributionItemVO> getCsatDistribution();

    /** 分坐席 CSAT 均分，按均分倒序 */
    List<CsatByAgentItemVO> getCsatByAgent(@Param("limit")  int limit,
                                            @Param("offset") int offset);

    /**
     * CSAT 概览统计（支持时间范围，按 requested_at 过滤）。
     *
     * @param startDate 开始日期（含）
     * @param endDate   结束日期（含）
     */
    CsatOverviewVO getCsatOverview(@Param("startDate") LocalDate startDate,
                                   @Param("endDate")   LocalDate endDate);

    // ============================================================
    // 个人数据（按当前登录座席 agentId 过滤）
    // ============================================================

    /**
     * 当前座席的概览指标：今日/总计接待会话数、平均等待/处理/首响时长。
     *
     * @param agentId 当前登录座席 ID（cs_conversation.agent_id 是 varchar，MyBatis 自动转换）
     */
    DashboardOverviewVO getMyOverview(@Param("agentId") Long agentId);

    /**
     * 当前座席的工作量统计。
     *
     * @param agentId 当前登录座席 ID
     */
    AgentWorkloadItemVO getMyWorkload(@Param("agentId") Long agentId);

    /**
     * 当前座席的 CSAT 概览统计（支持时间范围）。
     *
     * @param agentId   当前登录座席 ID（cs_csat_rating.agent_id 是 bigint）
     * @param startDate 开始日期（含）
     * @param endDate   结束日期（含）
     */
    CsatOverviewVO getMyCsatOverview(@Param("agentId")   Long agentId,
                                     @Param("startDate") LocalDate startDate,
                                     @Param("endDate")   LocalDate endDate);
}
