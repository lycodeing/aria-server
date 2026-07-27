package com.aria.conversation.infrastructure.persistence.entity;

import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * SLA 违规记录实体（对应 cs_conversation.cs_sla_breach 表）。
 *
 * <p>{@code breachType} 使用 {@link BreachType} 枚举，{@link com.baomidou.mybatisplus.annotation.EnumValue} 自动与 DB VARCHAR 映射；
 * {@code stage} 使用 {@link BreachStage} 枚举，同理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(schema = "cs_conversation", value = "cs_sla_breach")
public class SlaBreachEntity {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联会话 ID */
    private String sessionId;

    /** 关联 SLA 策略 ID */
    private Long policyId;

    /** 违规类型：WAIT / FRT / HANDLE */
    private BreachType breachType;

    /** 违规阶段：WARNING / BREACH */
    private BreachStage stage;

    /** 目标时间（秒） */
    private Integer targetSec;

    /** 预警时间（秒） */
    private Integer warnAtSec;

    /** 实际已用时间（秒） */
    private Integer actualSec;

    /** 违规发生时间 */
    private OffsetDateTime breachAt;

    /** SSE 告警发送时间（null 表示未发送） */
    private OffsetDateTime alertedAt;

    /** 升级执行时间（null 表示未升级） */
    private OffsetDateTime escalatedAt;

    /** Webhook 推送时间（null 表示未推送） */
    private OffsetDateTime webhookNotifiedAt;

    /** 记录创建时间 */
    private LocalDateTime createTime;
}
