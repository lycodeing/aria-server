package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * SLA 违规记录 Mapper。
 *
 * <p>单表 CRUD 优先使用 LambdaWrapper；需要跨列更新或原生 SQL 的方法使用 {@code @Update}/{@code @Select}。
 */
@Mapper
public interface SlaBreachMapper extends BaseMapper<SlaBreachEntity> {

    /**
     * 检查指定会话是否已存在某类型 + 阶段的违规记录（幂等写入前置校验）。
     *
     * @param sessionId 会话唯一标识
     * @param type      违规类型
     * @param stage     违规阶段
     * @return true 表示已存在，无需重复插入
     */
    default boolean existsBySessionTypeAndStage(String sessionId, BreachType type, BreachStage stage) {
        return exists(Wrappers.<SlaBreachEntity>lambdaQuery()
                .eq(SlaBreachEntity::getSessionId, sessionId)
                .eq(SlaBreachEntity::getBreachType, type.name())
                .eq(SlaBreachEntity::getStage, stage.name()));
    }

    /**
     * 记录 SSE 告警发送时间（幂等，仅写一次即可；重复调用无副作用）。
     *
     * @param id 违规记录主键
     * @param at 告警发送时间
     */
    @Update("UPDATE cs_conversation.cs_sla_breach SET alerted_at = #{at} WHERE id = #{id}")
    void updateAlertedAt(@Param("id") Long id, @Param("at") OffsetDateTime at);

    /**
     * 记录升级执行时间。
     *
     * @param id 违规记录主键
     * @param at 升级执行时间
     */
    @Update("UPDATE cs_conversation.cs_sla_breach SET escalated_at = #{at} WHERE id = #{id}")
    void updateEscalatedAt(@Param("id") Long id, @Param("at") OffsetDateTime at);

    /**
     * 批量记录 Webhook 推送时间。
     * ids 为空时直接返回，防止生成 WHERE id IN () 导致 PostgreSQL 语法错误。
     *
     * @param ids 违规记录主键列表（空列表安全跳过）
     * @param at  推送执行时间
     */
    default void updateWebhookNotifiedAt(List<Long> ids, OffsetDateTime at) {
        if (ids == null || ids.isEmpty()) return;
        doUpdateWebhookNotifiedAt(ids, at);
    }

    @Update("<script>UPDATE cs_conversation.cs_sla_breach " +
            "SET webhook_notified_at = #{at} " +
            "WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    void doUpdateWebhookNotifiedAt(@Param("ids") List<Long> ids,
                                    @Param("at") OffsetDateTime at);

    /**
     * 统计今日（从 todayStart 至今）发生过正式违规（stage=BREACH）的不重复会话数，
     * 用于仪表盘 SLA 健康度指标。
     *
     * @param todayStart 今日零点（Asia/Shanghai 偏移时间，OffsetDateTime）
     * @return 发生违规的不重复会话数
     */
    @Select("SELECT COUNT(DISTINCT session_id) FROM cs_conversation.cs_sla_breach " +
            "WHERE stage = 'BREACH' AND breach_at >= #{todayStart}")
    long countDistinctBreachedSessionsToday(@Param("todayStart") OffsetDateTime todayStart);
}
