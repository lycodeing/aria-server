package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.persistence.DashboardStatsRepository;
import com.aria.conversation.interfaces.rest.vo.AgentWorkloadItemVO;
import com.aria.conversation.interfaces.rest.vo.ComplexityDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.ConversationTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.CsatByAgentItemVO;
import com.aria.conversation.interfaces.rest.vo.CsatDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.CsatTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.DashboardOverviewVO;
import com.aria.conversation.interfaces.rest.vo.EfficiencyTrendItemVO;
import com.aria.conversation.interfaces.rest.vo.RecentSessionVO;
import com.aria.conversation.interfaces.rest.vo.StatusDistributionItemVO;
import com.aria.conversation.interfaces.rest.vo.TagDistributionItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Dashboard 统计应用服务。
 *
 * <p>负责组合 {@link DashboardStatsRepository} 的多次聚合查询，
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

    private final DashboardStatsRepository statsRepository;

    /**
     * 获取概览指标（analytics 页面顶部卡片）。
     *
     * <p>组合多次聚合查询，一次性返回所有概览数据。
     * 各查询独立执行，单次查询失败不影响其他指标（返回 0）。
     *
     * @return 概览指标 VO
     */
    public DashboardOverviewVO getOverview() {
        return DashboardOverviewVO.builder()
                .todayConversationCount(safeCount(statsRepository::countTodayConversations))
                .totalConversationCount(safeCount(statsRepository::countTotalConversations))
                .activeConversationCount(safeCount(() -> statsRepository.countByStatus("ACTIVE")))
                .waitingConversationCount(safeCount(() -> statsRepository.countByStatus("WAITING")))
                .totalUserCount(safeCount(statsRepository::countTotalUsers))
                .totalMessageCount(safeCount(statsRepository::countTotalMessages))
                .aiMessageCount(safeCount(() -> statsRepository.countMessagesByRole("assistant")))
                .agentMessageCount(safeCount(() -> statsRepository.countMessagesByRole("agent")))
                .avgWaitSeconds(safeCount(statsRepository::avgWaitSeconds))
                .avgHandleSeconds(safeCount(statsRepository::avgHandleSeconds))
                .avgFirstReplySeconds(safeCount(statsRepository::avgFirstReplySeconds))
                .csatAvgScore(safeDouble(statsRepository::csatAvgScore))
                .csatResponseRate(safeDouble(statsRepository::csatResponseRate))
                .csatRatedCount(safeCount(statsRepository::csatRatedCount))
                .build();
    }

    /**
     * 会话趋势（analytics 页面折线图）。
     *
     * @return 按月聚合的趋势数据列表
     */
    public List<ConversationTrendItemVO> getConversationTrends() {
        return getConversationTrends("month", null);
    }

    /**
     * 会话趋势（支持时间范围，按天聚合）。
     *
     * @param rangeType 时间范围类型：month / week / custom
     * @param days      仅 rangeType=custom 时生效，往前推 N 天
     * @return 按天聚合的趋势数据列表
     */
    public List<ConversationTrendItemVO> getConversationTrends(String rangeType, Integer days) {
        LocalDate[] range = resolveRange(rangeType, days);
        return statsRepository.getConversationTrendsByRange(range[0], range[1]);
    }

    /**
     * 消息量趋势（analytics 页面柱状图）。
     *
     * @return 按月聚合的消息量数据列表
     */
    public List<ConversationTrendItemVO> getMessageTrends() {
        return getMessageTrends("month", null);
    }

    /**
     * 消息量趋势（支持时间范围，按天聚合）。
     *
     * @param rangeType 时间范围类型：month / week / custom
     * @param days      仅 rangeType=custom 时生效，往前推 N 天
     * @return 按天聚合的消息量数据列表
     */
    public List<ConversationTrendItemVO> getMessageTrends(String rangeType, Integer days) {
        LocalDate[] range = resolveRange(rangeType, days);
        return statsRepository.getMessageTrendsByRange(range[0], range[1]);
    }

    /**
     * 效率趋势（支持时间范围，按天聚合）。
     *
     * @param rangeType 时间范围类型：month / week / custom
     * @param days      仅 rangeType=custom 时生效，往前推 N 天
     * @return 按天聚合的效率趋势数据列表
     */
    public List<EfficiencyTrendItemVO> getEfficiencyTrends(String rangeType, Integer days) {
        LocalDate[] range = resolveRange(rangeType, days);
        return statsRepository.getEfficiencyTrends(range[0], range[1]);
    }

    /**
     * 会话状态分布（analytics 页面饼图）。
     *
     * @return 状态分布数据列表
     */
    public List<StatusDistributionItemVO> getStatusDistribution() {
        return statsRepository.getStatusDistribution();
    }

    /**
     * 问题标签分布（analytics 页面玫瑰图）。
     *
     * @return 标签分布数据列表
     */
    public List<TagDistributionItemVO> getTagDistribution() {
        return statsRepository.getTagDistribution();
    }

    /**
     * 最近会话列表（workspace 页面最近动态）。
     *
     * @param limit 返回条数，默认 10
     * @return 最近会话列表
     */
    public List<RecentSessionVO> getRecentSessions(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return statsRepository.getRecentSessions(safeLimit);
    }

    /**
     * 座席工作量统计（workspace 页面项目卡片）。
     *
     * @return 座席工作量列表
     */
    public List<AgentWorkloadItemVO> getAgentWorkload() {
        return statsRepository.getAgentWorkload();
    }

    /**
     * 会话复杂度分布（analytics 页面环形图）。
     * 以每条会话的消息数分桶：≤5=SIMPLE，6–15=MEDIUM，&gt;15=COMPLEX。
     *
     * @return 复杂度分布列表
     */
    public List<ComplexityDistributionItemVO> getComplexityDistribution() {
        return statsRepository.getComplexityDistribution();
    }

    // ============================================================
    // 内部工具方法
    // ============================================================

    /**
     * 安全执行计数查询，异常时返回 0 并记录日志。
     * 避免单个指标查询失败导致整个概览接口 500。
     * 使用 {@link LongSupplier} 避免自动装箱，类型更安全。
     */
    private long safeCount(LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (Exception e) {
            log.warn("Dashboard 统计查询失败，返回 0: {}", e.getMessage());
            return 0L;
        }
    }

    private double safeDouble(DoubleSupplier supplier) {
        try { return supplier.getAsDouble(); }
        catch (Exception e) { log.warn("CSAT 统计查询失败，返回 0.0: {}", e.getMessage()); return 0.0; }
    }

    public List<CsatTrendItemVO> getCsatTrend(String rangeType, Integer days) {
        LocalDate[] range = resolveRange(rangeType, days);
        return statsRepository.getCsatTrend(range[0], range[1]);
    }

    public List<CsatDistributionItemVO> getCsatDistribution() {
        return statsRepository.getCsatDistribution();
    }

    public List<CsatByAgentItemVO> getCsatByAgent(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page - 1, 0) * safeSize;
        return statsRepository.getCsatByAgent(safeSize, offset);
    }

    /**
     * 将 rangeType/days 参数解析为 [startDate, endDate]（含两端）。
     * <ul>
     *   <li>month  → 本月第一天 ~ 今天</li>
     *   <li>week   → 本周一 ~ 今天</li>
     *   <li>custom → 今天 - days + 1 ~ 今天（days 默认 7）</li>
     * </ul>
     */
    private LocalDate[] resolveRange(String rangeType, Integer days) {
        LocalDate today = LocalDate.now();
        LocalDate start = switch (rangeType == null ? "month" : rangeType) {
            case "week"   -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "custom" -> today.minusDays(Math.max(1, days == null ? 7 : days) - 1);
            default       -> today.withDayOfMonth(1); // month
        };
        return new LocalDate[]{start, today};
    }
}
