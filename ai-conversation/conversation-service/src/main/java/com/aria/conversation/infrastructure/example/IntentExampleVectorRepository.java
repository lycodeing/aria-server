package com.aria.conversation.infrastructure.example;

import com.aria.common.core.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图触发历史案例向量仓储。
 *
 * <p>存储结构：{@code cs_conversation.intent_example_vectors} 表（PostgreSQL + pgvector）。
 * 数据来源：
 * <ol>
 *   <li>人工标注：客服坐席接单后在工单系统标注意图，同步到此表</li>
 *   <li>自动积累：Tier3 LLM 高置信度（>= autoAccumulateMinConfidence）分类结果自动入库</li>
 * </ol>
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class IntentExampleVectorRepository {

    private static final String TABLE_NAME = "cs_conversation.intent_example_vectors";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 检索与 query 语义最相似的历史案例，按意图 code 分组返回。
     *
     * <p><b>参数绑定说明：</b>{@code float[]} 通过 {@link VectorUtils#toStr(float[])} 转为
     * {@code "[0.1,0.2,...]"} 格式字符串，再以 {@code ?::vector} 绑定到 pgvector 列。
     * SQL 中 {@code ?::vector} 只出现一次（ORDER BY 子查询中使用同一参数）。
     *
     * @param queryEmbedding query embedding 向量（float[]，内部自动序列化）
     * @param topK           每个意图返回的最大案例数（建议 2-3）
     * @param limit          检索候选总数
     * @return intentCode → 历史案例文本列表（按相似度排序）
     */
    public Map<String, List<String>> findSimilarByIntent(
            float[] queryEmbedding, int topK, int limit) {
        String vecStr = VectorUtils.toStr(queryEmbedding);
        String sql = """
                SELECT intent_code, message_text
                FROM %s
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(TABLE_NAME);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, vecStr, limit);

        Map<String, List<String>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String code = (String) row.get("intent_code");
            String text = (String) row.get("message_text");
            result.computeIfAbsent(code, k -> new ArrayList<>()).add(text);
        }
        // 每个意图只保留 topK 条
        result.replaceAll((code, texts) ->
                texts.size() > topK ? texts.subList(0, topK) : texts);
        return result;
    }

    /**
     * 幂等保存意图触发案例（原子操作，防止并发双写）。
     *
     * <p><b>I4 修复：</b>使用 {@code INSERT ... ON CONFLICT DO NOTHING} 保证原子性，
     * 避免应用层"先查后插"的非原子操作导致并发竞争时语义重复样本入库。
     *
     * @param intentCode    意图 code
     * @param messageText   原始用户消息文本
     * @param embedding     消息 embedding 向量
     * @param autoConfirmed 是否为自动积累（非人工确认）
     */
    public void saveIfAbsent(String intentCode, String messageText,
                              float[] embedding, boolean autoConfirmed) {
        String vecStr = VectorUtils.toStr(embedding);
        String sql = """
                INSERT INTO %s (intent_code, message_text, embedding, auto_confirmed)
                VALUES (?, ?, ?::vector, ?)
                ON CONFLICT DO NOTHING
                """.formatted(TABLE_NAME);
        try {
            jdbcTemplate.update(sql, intentCode, messageText, vecStr, autoConfirmed);
            log.debug("[ExampleRepo] 保存案例 intentCode={} autoConfirmed={}",
                    intentCode, autoConfirmed);
        } catch (Exception e) {
            log.warn("[ExampleRepo] 保存案例失败 intentCode={}", intentCode, e);
        }
    }
}
