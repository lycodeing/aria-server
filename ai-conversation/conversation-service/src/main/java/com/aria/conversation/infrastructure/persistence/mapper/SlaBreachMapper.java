package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * SLA 违规记录 Mapper。
 *
 * <p>单表 CRUD 优先使用 LambdaWrapper；COUNT(DISTINCT ...) 等聚合查询放在 XML 文件中。
 */
@Mapper
public interface SlaBreachMapper extends BaseMapper<SlaBreachEntity> {

    /**
     * 检查指定会话是否已存在某类型 + 阶段的违规记录（幂等写入前置校验）。
     */
    default boolean existsBySessionTypeAndStage(String sessionId, BreachType type, BreachStage stage) {
        return exists(Wrappers.<SlaBreachEntity>lambdaQuery()
                .eq(SlaBreachEntity::getSessionId, sessionId)
                .eq(SlaBreachEntity::getBreachType, type)
                .eq(SlaBreachEntity::getStage, stage));
    }

    /**
     * 记录 SSE 告警发送时间（幂等，仅写一次即可；重复调用无副作用）。
     */
    default void updateAlertedAt(Long id, OffsetDateTime at) {
        update(Wrappers.<SlaBreachEntity>lambdaUpdate()
                .set(SlaBreachEntity::getAlertedAt, at)
                .eq(SlaBreachEntity::getId, id));
    }

    /**
     * 记录升级执行时间。
     */
    default void updateEscalatedAt(Long id, OffsetDateTime at) {
        update(Wrappers.<SlaBreachEntity>lambdaUpdate()
                .set(SlaBreachEntity::getEscalatedAt, at)
                .eq(SlaBreachEntity::getId, id));
    }

    /**
     * 批量记录 Webhook 推送时间（空列表安全跳过）。
     */
    default void updateWebhookNotifiedAt(List<Long> ids, OffsetDateTime at) {
        if (ids == null || ids.isEmpty()) return;
        update(Wrappers.<SlaBreachEntity>lambdaUpdate()
                .set(SlaBreachEntity::getWebhookNotifiedAt, at)
                .in(SlaBreachEntity::getId, ids));
    }

    /**
     * 统计今日发生过正式违规（stage=BREACH）的不重复会话数，用于仪表盘 SLA 健康度指标。
     * SQL 使用 COUNT(DISTINCT session_id)，定义在 SlaBreachMapper.xml 中。
     *
     * @param todayStart 今日零点（Asia/Shanghai 偏移时间）
     * @return 发生违规的不重复会话数
     */
    long countDistinctBreachedSessionsToday(@Param("todayStart") OffsetDateTime todayStart);
}
