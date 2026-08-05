package com.aria.conversation.infrastructure.observability;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * LLM Token 成本日志 Mapper。
 */
@Mapper
public interface LlmCostLogMapper extends BaseMapper<LlmCostLogEntity> {

    /**
     * 聚合指定时间区间的 Token 总量与调用次数。
     *
     * @param since     起始时间（含）
     * @param modelName 可选模型过滤，null 表示全部
     * @return 单行聚合结果
     */
    @Select("""
            <script>
            SELECT
                COALESCE(SUM(input_tokens), 0)  AS total_input,
                COALESCE(SUM(output_tokens), 0) AS total_output,
                COALESCE(SUM(total_tokens), 0)  AS total_tokens,
                COUNT(*)                        AS call_count
            FROM cs_conversation.cs_llm_cost_log
            WHERE created_at &gt;= #{since}
            <if test="modelName != null">
                AND model_name = #{modelName}
            </if>
            </script>
            """)
    Map<String, Object> aggregate(@Param("since") OffsetDateTime since,
                                  @Param("modelName") String modelName);

    /**
     * 按模型维度聚合 Token 消耗与调用次数。
     *
     * @param since 起始时间（含）
     * @return 每行含 model_name、total_tokens、call_count
     */
    @Select("""
            SELECT model_name, COALESCE(SUM(total_tokens), 0) AS total_tokens, COUNT(*) AS call_count
            FROM cs_conversation.cs_llm_cost_log
            WHERE created_at >= #{since}
            GROUP BY model_name
            ORDER BY total_tokens DESC
            """)
    List<Map<String, Object>> aggregateByModel(@Param("since") OffsetDateTime since);

    /**
     * 按调用类型维度聚合 Token 消耗。
     *
     * @param since 起始时间（含）
     * @return 每行含 call_type、total_tokens
     */
    @Select("""
            SELECT call_type, COALESCE(SUM(total_tokens), 0) AS total_tokens
            FROM cs_conversation.cs_llm_cost_log
            WHERE created_at >= #{since}
            GROUP BY call_type
            ORDER BY total_tokens DESC
            """)
    List<Map<String, Object>> aggregateByCallType(@Param("since") OffsetDateTime since);
}
