package com.aria.conversation.infrastructure.observability;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索质量日志 Mapper。
 */
@Mapper
public interface RagMissLogMapper extends BaseMapper<RagMissLogEntity> {

    /**
     * 聚合指定时间区间的检索质量概览：总检索数、miss 数、平均 top1 分数。
     *
     * @param since 起始时间（含）
     * @return 单行聚合结果
     */
    @Select("""
            SELECT
                COUNT(*)                                          AS total,
                COALESCE(SUM(CASE WHEN is_miss THEN 1 ELSE 0 END), 0) AS miss_count,
                AVG(top1_score)                                   AS avg_top1_score
            FROM cs_conversation.cs_rag_miss_log
            WHERE created_at >= #{since}
            """)
    Map<String, Object> aggregate(@Param("since") OffsetDateTime since);

    /**
     * 返回指定时间区间内 miss 次数最多的查询，用于驱动 KB 补充。
     *
     * @param since 起始时间（含）
     * @param limit 返回条数
     * @return 每行含 query_text、miss_count
     */
    @Select("""
            SELECT query_text, COUNT(*) AS miss_count
            FROM cs_conversation.cs_rag_miss_log
            WHERE is_miss = TRUE AND created_at >= #{since}
            GROUP BY query_text
            ORDER BY miss_count DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> topMissQueries(@Param("since") OffsetDateTime since,
                                              @Param("limit") int limit);
}
