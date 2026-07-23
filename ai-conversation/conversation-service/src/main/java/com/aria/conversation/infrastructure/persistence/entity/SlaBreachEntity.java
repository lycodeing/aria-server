package com.aria.conversation.infrastructure.persistence.entity;

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
 * <p>{@code breachType} 存储 {@code BreachType.name()}（WAIT / FRT / HANDLE）；
 * {@code stage} 存储 {@code BreachStage.name()}（WARNING / BREACH）。
 * 使用字符串而非枚举，避免 MyBatis-Plus 枚举映射与 autoResultMap 的潜在冲突。
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
    private String breachType;

    /** 违规阶段：WARNING / BREACH */
    private String stage;

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

    /** 记录创建时间 */
    private LocalDateTime createTime;
}
