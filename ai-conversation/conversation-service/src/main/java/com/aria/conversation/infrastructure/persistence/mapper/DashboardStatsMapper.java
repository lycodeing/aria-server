package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.interfaces.rest.vo.AgentWorkloadItemVO;
import com.aria.conversation.interfaces.rest.vo.ComplexityDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.ConversationTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.EfficiencyTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.RecentSessionVO;
import com.aria.conversation.interfaces.rest.vo.StatusDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.TagDistributionItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
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
     * tag != 'AI 对话' 为人工会话（含 WAITING/ACTIVE/CLOSED 转人工），
     * tag = 'AI 对话' 为纯 AI 会话；比 agent_id 判断更准确，
     * 避免将 agent_id=NULL 的 WAITING 会话误算为 AI 会话。
     * 返回最近 12 个月的数据。
     */
    @Select("""
            SELECT
                TO_CHAR(DATE_TRUNC('month', started_at), 'YYYY-MM')        AS month,
                COUNT(*) FILTER (WHERE tag != 'AI 对话')                   AS "humanCount",
                COUNT(*) FILTER (WHERE tag = 'AI 对话')                    AS "aiCount"
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
                c.accepted_at     AS "acceptedAt",
                c.ended_at        AS "endedAt",
                c.closed_by       AS "closedBy",
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

    // ============================================================
    // 时长类指标（需要 accepted_at / first_reply_at）
    // ============================================================

    /**
     * 平均等待时长（秒）：从入队到座席接入。
     * 仅统计已接入的会话（accepted_at IS NOT NULL）。
     */
    @Select("""
            SELECT COALESCE(
                EXTRACT(EPOCH FROM AVG(accepted_at - started_at))::bigint,
                0
            )
            FROM cs_conversation.cs_conversation
            WHERE accepted_at IS NOT NULL
            """)
    long avgWaitSeconds();

    /**
     * 平均处理时长（秒）：从座席接入到会话结束。
     * 仅统计已关闭的人工会话（ended_at IS NOT NULL AND accepted_at IS NOT NULL）。
     */
    @Select("""
            SELECT COALESCE(
                EXTRACT(EPOCH FROM AVG(ended_at - accepted_at))::bigint,
                0
            )
            FROM cs_conversation.cs_conversation
            WHERE accepted_at IS NOT NULL
              AND ended_at IS NOT NULL
            """)
    long avgHandleSeconds();

    /**
     * 平均首次响应时长（秒）：从座席接入到首条座席回复。
     * 仅统计已有首条回复的会话（first_reply_at IS NOT NULL AND accepted_at IS NOT NULL）。
     */
    @Select("""
            SELECT COALESCE(
                EXTRACT(EPOCH FROM AVG(first_reply_at - accepted_at))::bigint,
                0
            )
            FROM cs_conversation.cs_conversation
            WHERE accepted_at IS NOT NULL
              AND first_reply_at IS NOT NULL
            """)
    long avgFirstReplySeconds();

    // ============================================================
    // 按时间范围聚合（按天，支持时间范围筛选）
    // ============================================================

    /**
     * 会话趋势（按天聚合，支持时间范围）。
     * 返回 [startDate, endDate] 区间内每天的人工/AI 会话量。
     * 以 tag='AI 对话' 区分纯 AI 会话，其余均视为人工会话（含 WAITING 状态）。
     */
    @Select("""
            SELECT
                TO_CHAR(DATE_TRUNC('day', started_at), 'YYYY-MM-DD')       AS month,
                COUNT(*) FILTER (WHERE tag != 'AI 对话')                    AS "humanCount",
                COUNT(*) FILTER (WHERE tag = 'AI 对话')                     AS "aiCount"
            FROM cs_conversation.cs_conversation
            WHERE started_at >= #{startDate}::date
              AND started_at < #{endDate}::date + INTERVAL '1 day'
            GROUP BY DATE_TRUNC('day', started_at)
            ORDER BY month
            """)
    List<ConversationTrendItemVO> getConversationTrendsByRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate);

    /**
     * 消息量趋势（按天聚合，支持时间范围）。
     * 区分 agent（人工）和 assistant（AI）两类角色。
     */
    @Select("""
            SELECT
                TO_CHAR(DATE_TRUNC('day', created_at), 'YYYY-MM-DD')  AS month,
                COUNT(*) FILTER (WHERE role = 'agent')                 AS "humanCount",
                COUNT(*) FILTER (WHERE role = 'assistant')             AS "aiCount"
            FROM cs_conversation.cs_conversation_message
            WHERE created_at >= #{startDate}::date
              AND created_at < #{endDate}::date + INTERVAL '1 day'
            GROUP BY DATE_TRUNC('day', created_at)
            ORDER BY month
            """)
    List<ConversationTrendItemVO> getMessageTrendsByRange(
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate);

    /**
     * 效率趋势（按天聚合，支持时间范围）。
     * 返回每天的平均等待/处理/首次回复时长（秒）。
     * 仅统计 accepted_at IS NOT NULL 的会话。
     */
    @Select("""
            SELECT
                TO_CHAR(DATE_TRUNC('day', accepted_at), 'YYYY-MM-DD')                                       AS date,
                COALESCE(EXTRACT(EPOCH FROM AVG(accepted_at - started_at))::bigint,     0) AS "avgWaitSeconds",
                COALESCE(EXTRACT(EPOCH FROM AVG(ended_at - accepted_at))::bigint,       0) AS "avgHandleSeconds",
                COALESCE(EXTRACT(EPOCH FROM AVG(first_reply_at - accepted_at))::bigint, 0) AS "avgFirstReplySeconds"
            FROM cs_conversation.cs_conversation
            WHERE accepted_at IS NOT NULL
              AND accepted_at >= #{startDate}::date
              AND accepted_at < #{endDate}::date + INTERVAL '1 day'
            GROUP BY DATE_TRUNC('day', accepted_at)
            ORDER BY date
            """)
    List<EfficiencyTrendItemVO> getEfficiencyTrends(
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate);

    /**
     * 从 cs_auth.system_config 读取单个配置值。
     * 跨 schema 查询，与本服务在同一 PostgreSQL 实例中。
     * 找不到或已禁用时返回 {@code defaultValue}。
     */
    @Select("SELECT COALESCE(MAX(config_value), #{defaultValue}) " +
            "FROM cs_auth.system_config " +
            "WHERE config_key = #{key} AND is_enabled = true AND deleted_at IS NULL")
    String getConfigValue(@Param("key") String key, @Param("defaultValue") String defaultValue);

    /**
     * 会话复杂度分布。
     * 以每条会话的消息数作为复杂度度量，分三档：
     * <ul>
     *   <li>SIMPLE  — 消息数 ≤ simpleMax</li>
     *   <li>MEDIUM  — 消息数 simpleMax+1 ~ mediumMax</li>
     *   <li>COMPLEX — 消息数 &gt; mediumMax</li>
     * </ul>
     * 阈值由调用方从 system_config 动态读取，避免硬编码。
     */
    @Select("""
            SELECT
                CASE
                    WHEN msg_count <= #{simpleMax}  THEN 'SIMPLE'
                    WHEN msg_count <= #{mediumMax}  THEN 'MEDIUM'
                    ELSE                                 'COMPLEX'
                END                AS complexity,
                COUNT(*)::int      AS count
            FROM (
                SELECT
                    c.session_id,
                    COUNT(m.id) AS msg_count
                FROM cs_conversation.cs_conversation c
                LEFT JOIN cs_conversation.cs_conversation_message m
                       ON m.session_id = c.session_id
                GROUP BY c.session_id
            ) sub
            GROUP BY complexity
            ORDER BY complexity
            """)
    List<ComplexityDistributionItemVO> getComplexityDistribution(
            @Param("simpleMax") int simpleMax,
            @Param("mediumMax") int mediumMax);
}
