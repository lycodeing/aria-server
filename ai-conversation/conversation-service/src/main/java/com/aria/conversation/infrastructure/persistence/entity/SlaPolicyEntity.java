package com.aria.conversation.infrastructure.persistence.entity;

import com.aria.conversation.domain.model.SlaBreachActions;
import com.aria.conversation.infrastructure.config.SlaBreachActionsTypeHandler;
import com.aria.conversation.infrastructure.config.StringListTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SLA 策略实体（对应 cs_conversation.cs_sla_policy 表）。
 *
 * <p>{@code matchVisitorTags}、{@code matchTransferTags}、{@code actions} 字段以 JSONB 存储，
 * 通过 {@link SlaBreachActionsTypeHandler}/{@link StringListTypeHandler} 写入 PGobject，
 * 兼容 PostgreSQL JSONB 列；{@code autoResultMap=true} 开启结果映射。
 */
@Data
@TableName(schema = "cs_conversation", value = "cs_sla_policy", autoResultMap = true)
public class SlaPolicyEntity {

    /**
     * 主键（自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 策略名称
     */
    private String name;

    /**
     * 是否启用
     */
    private Boolean isEnabled;

    /**
     * 优先级（越大越优先匹配）
     */
    private Integer priority;

    /**
     * 访客标签匹配列表（JSON 数组），空或 null 表示不限制
     */
    @TableField(typeHandler = StringListTypeHandler.class)
    private List<String> matchVisitorTags;

    /**
     * 转接标签匹配列表（JSON 数组），空或 null 表示不限制
     */
    @TableField(typeHandler = StringListTypeHandler.class)
    private List<String> matchTransferTags;

    /**
     * 计时模式：{@code BUSINESS_HOURS}（工作时间）或 {@code CALENDAR}（自然时间）。
     * 与 IBusinessHoursCalculator 联合使用。
     */
    private String timeMode;

    /**
     * 等待时长目标（秒）—— WAITING 阶段超时
     */
    private Integer waitTimeTargetSec;

    /**
     * 首次响应时长目标（秒）—— FRT 超时
     */
    private Integer frtTargetSec;

    /**
     * 处理时长目标（秒）—— 整个会话超时
     */
    private Integer handleTimeTargetSec;

    /**
     * 预警阈值百分比（0-100），达到目标时间的该百分比时触发 WARNING
     */
    private Integer warningThresholdPct;

    /**
     * 违规行为配置（JSON 对象）
     */
    @TableField(typeHandler = SlaBreachActionsTypeHandler.class)
    private SlaBreachActions actions;

    /**
     * 记录创建时间
     */
    private LocalDateTime createTime;

    /**
     * 记录最后更新时间
     */
    private LocalDateTime updateTime;
}
