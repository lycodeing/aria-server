package com.aria.conversation.infrastructure.observability;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * {@link IntentTierStatEntity} 持久化与聚合查询。
 */
@Mapper
public interface IntentTierStatMapper extends BaseMapper<IntentTierStatEntity> {

    /**
     * 聚合指定时间区间的三层命中率与平均延迟。
     *
     * @param since      起始时间（含）
     * @param domainCode 可选域过滤，null 表示全部
     * @return 单行聚合结果，key 为下列列名
     */
    @Select("""
            <script>
            SELECT
                COUNT(*)                                             AS total,
                COALESCE(SUM(CASE WHEN tier1_hit      THEN 1 ELSE 0 END), 0) AS tier1_hit,
                COALESCE(SUM(CASE WHEN tier2_executed THEN 1 ELSE 0 END), 0) AS tier2_exec,
                COALESCE(SUM(CASE WHEN tier2_hit      THEN 1 ELSE 0 END), 0) AS tier2_hit,
                COALESCE(SUM(CASE WHEN tier3_executed THEN 1 ELSE 0 END), 0) AS tier3_exec,
                COALESCE(SUM(CASE WHEN tier3_hit      THEN 1 ELSE 0 END), 0) AS tier3_hit,
                AVG(tier1_latency_ms)                                AS avg_rule_ms,
                AVG(tier2_latency_ms)                                AS avg_embedding_ms,
                AVG(tier3_latency_ms)                                AS avg_llm_ms
            FROM cs_conversation.cs_intent_tier_stat
            WHERE created_at &gt;= #{since}
            <if test="domainCode != null">
                AND domain_code = #{domainCode}
            </if>
            </script>
            """)
    Map<String, Object> aggregate(@Param("since") OffsetDateTime since,
                                  @Param("domainCode") String domainCode);
}
