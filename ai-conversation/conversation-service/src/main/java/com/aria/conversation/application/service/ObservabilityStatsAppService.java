package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.observability.IntentTierStatMapper;
import com.aria.conversation.infrastructure.observability.LlmCostLogMapper;
import com.aria.conversation.infrastructure.observability.RagMissLogMapper;
import com.aria.conversation.interfaces.dto.StatsPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测性统计应用服务。
 *
 * <p>为管理台提供三类落库指标的聚合查询：DIT 三层命中率、RAG 检索质量、LLM Token 成本。
 * 全部基于明细表实时 {@code GROUP BY} 聚合，查询量小，不引入缓存。
 */
@Service
@RequiredArgsConstructor
public class ObservabilityStatsAppService {

    private final IntentTierStatMapper intentTierStatMapper;
    private final RagMissLogMapper ragMissLogMapper;
    private final LlmCostLogMapper llmCostLogMapper;

    /** miss 查询榜返回条数上限 */
    private static final int TOP_MISS_LIMIT = 20;

    /**
     * DIT 三层命中率与延迟报表。
     *
     * @param period     统计周期
     * @param domainCode 可选域过滤
     */
    public Map<String, Object> intentClassificationStats(StatsPeriod period, String domainCode) {
        OffsetDateTime since = period.startTime();
        Map<String, Object> row = intentTierStatMapper.aggregate(since, domainCode);

        long total = toLong(row.get("total"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period.name());
        result.put("totalClassifications", total);
        result.put("tier1HitRate", ratio(toLong(row.get("tier1_hit")), total));
        result.put("tier2HitRate", ratio(toLong(row.get("tier2_hit")), total));
        result.put("tier3TriggerRate", ratio(toLong(row.get("tier3_exec")), total));

        Map<String, Object> avgLatency = new LinkedHashMap<>();
        avgLatency.put("RULE", roundOrNull(row.get("avg_rule_ms")));
        avgLatency.put("EMBEDDING", roundOrNull(row.get("avg_embedding_ms")));
        avgLatency.put("LLM", roundOrNull(row.get("avg_llm_ms")));
        result.put("avgLatencyMs", avgLatency);
        return result;
    }

    /**
     * RAG 检索质量报表：miss 率 + 平均 top1 分数 + miss 榜。
     *
     * @param period 统计周期
     */
    public Map<String, Object> ragQualityStats(StatsPeriod period) {
        OffsetDateTime since = period.startTime();
        Map<String, Object> row = ragMissLogMapper.aggregate(since);
        long total = toLong(row.get("total"));
        long missCount = toLong(row.get("miss_count"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period.name());
        result.put("totalSearches", total);
        result.put("missCount", missCount);
        result.put("missRate", ratio(missCount, total));
        result.put("avgTop1Score", roundOrNull(row.get("avg_top1_score")));
        result.put("topMissQueries", ragMissLogMapper.topMissQueries(since, TOP_MISS_LIMIT));
        return result;
    }

    /**
     * LLM Token 成本报表：总量 + 按模型 + 按调用类型。
     *
     * @param period    统计周期
     * @param modelName 可选模型过滤
     */
    public Map<String, Object> llmCostStats(StatsPeriod period, String modelName) {
        OffsetDateTime since = period.startTime();
        Map<String, Object> row = llmCostLogMapper.aggregate(since, modelName);
        long callCount = toLong(row.get("call_count"));
        long totalTokens = toLong(row.get("total_tokens"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period.name());
        result.put("totalInputTokens", toLong(row.get("total_input")));
        result.put("totalOutputTokens", toLong(row.get("total_output")));
        result.put("totalTokens", totalTokens);
        result.put("callCount", callCount);
        result.put("avgTokensPerCall", callCount == 0 ? 0 : Math.round((double) totalTokens / callCount));
        result.put("byModel", llmCostLogMapper.aggregateByModel(since));
        result.put("byCallType", llmCostLogMapper.aggregateByCallType(since));
        return result;
    }

    // -------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    /** 命中率保留 4 位小数；分母为 0 时返回 0。 */
    private static double ratio(long numerator, long denominator) {
        if (denominator == 0) return 0.0;
        return Math.round((double) numerator / denominator * 10000.0) / 10000.0;
    }

    /** 平均延迟保留整数毫秒；无数据（null）时返回 null。 */
    private static Long roundOrNull(Object avg) {
        if (avg == null) return null;
        return Math.round(((Number) avg).doubleValue());
    }
}
