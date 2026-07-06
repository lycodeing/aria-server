package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.interfaces.rest.vo.AgentWorkloadItemVO;
import com.aria.conversation.interfaces.rest.vo.ConversationTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.RecentSessionVO;
import com.aria.conversation.interfaces.rest.vo.StatusDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.TagDistributionItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Dashboard 统计查询 Mapper。
 *
 * <p>专门用于 Dashboard 统计聚合查询，与 {@link ConversationMapper}（CRUD）分离，
 * 遵循单一职责原则。所有查询使用 @Select 原生 SQL，因为涉及跨 schema 聚合统计，
 * 无法通过 MyBatis-Plus LambdaWrapper 表达。
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
    @Select("SELECT COUNT(*) FROM cs_conversation.cs_conversation " +
            "WHERE started_at >= CURRENT_DATE")
    long countTodayConversations();

    /** 总会话量 */
    @Select("SELECT COUNT(*) FROM cs_conversation.cs_conversation")
    long countTotalConversations();

    /** 按状态统计会话数 */
    @Select("SELECT COUNT(*) FROM cs_conversation.cs_conversation " +
            "WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    /** 总用户数（cs_auth.sys_user 未删除） */
    @Select("SELECT COUNT(*) FROM cs_auth.sys_user WHERE deleted_at IS NULL")
    long countTotalUsers();

    /** 总消息数 */
    @Select("SELECT COUNT(*) FROM cs_conversation.cs_conversation_message")
    long countTotalMessages();

    /** 按角色统计消息数 */
    @Select("SELECT COUNT(*) FROM cs_conversation.cs_conversation_message " +
            "WHERE role = #{role}")
    long countMessagesByRole(@Param("role") String role);

    // ============================================================
    // 图表数据
    // ============================================================

    /**
     * 会话趋势（按月聚合，区分人工/AI）。
     * 有 agent_id 的为人工会话，无 agent_id 的为 AI 会话。
     * 返回最近 12 个月的数据。
     */
    @Select("""
            SELECT
                TO_CHAR(DATE_TRUNC('month', started_at), 'YYYY-MM')        AS month,
                COUNT(*) FILTER (WHERE agent_id IS NOT NULL)               AS "humanCount",
                COUNT(*) FILTER (WHERE agent_id IS NULL)                   AS "aiCount"
            FROM cs_conversation.cs_conversation
            WHERE started_at >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '11 months')
            GROUP BY DATE_TRUNC('month', started_at)
            ORDER BY month
            """)
    List<ConversationTrendItemVO> getMonthlyTrends();

    /** 月消息量趋势（按月聚合，用于柱状图） */
    @Select("""
            SELECT
                TO_CHAR(DATE_TRUNC('month', created_at), 'YYYY-MM') AS month,
                COUNT(*) AS count
            FROM cs_conversation.cs_conversation_message
            WHERE created_at >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '11 months')
            GROUP BY DATE_TRUNC('month', created_at)
            ORDER BY month
            """)
    List<ConversationTrendItemVO> getMonthlyMessageTrends();

    /** 会话状态分布 */
    @Select("""
            SELECT
                status,
                COUNT(*) AS count
            FROM cs_conversation.cs_conversation
            GROUP BY status
            """)
    List<StatusDistributionItemVO> getStatusDistribution();

    /**
     * 问题标签分布。
     * tag 为 NULL 的归类为"未分类"。
     */
    @Select("""
            SELECT
                COALESCE(tag, '未分类') AS tag,
                COUNT(*) AS count
            FROM cs_conversation.cs_conversation
            GROUP BY tag
            ORDER BY count DESC
            """)
    List<TagDistributionItemVO> getTagDistribution();

    // ============================================================
    // workspace 页面数据
    // ============================================================

    /**
     * 最近会话列表（含消息数子查询）。
     *
     * @param limit 返回条数
     */
    @Select("""
            SELECT
                c.session_id      AS "sessionId",
                c.visitor_name    AS "visitorName",
                c.tag             AS "tag",
                c.transfer_reason AS "transferReason",
                c.status          AS "status",
                c.agent_id        AS "agentId",
                c.started_at      AS "startedAt",
                c.ended_at        AS "endedAt",
                COALESCE(
                    (SELECT COUNT(*) FROM cs_conversation.cs_conversation_message m
                     WHERE m.session_id = c.session_id), 0
                )                  AS "messageCount"
            FROM cs_conversation.cs_conversation c
            ORDER BY c.started_at DESC
            LIMIT #{limit}
            """)
    List<RecentSessionVO> getRecentSessions(@Param("limit") int limit);

    /** 座席工作量统计（按 agent_id 聚合） */
    @Select("""
            SELECT
                agent_id       AS "agentId",
                COUNT(*)       AS "totalSessions",
                COUNT(*) FILTER (WHERE status = 'ACTIVE') AS "activeSessions"
            FROM cs_conversation.cs_conversation
            WHERE agent_id IS NOT NULL
            GROUP BY agent_id
            ORDER BY "totalSessions" DESC
            """)
    List<AgentWorkloadItemVO> getAgentWorkload();
}
