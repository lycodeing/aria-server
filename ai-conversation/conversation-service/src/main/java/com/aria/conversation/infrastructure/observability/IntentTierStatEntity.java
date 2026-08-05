package com.aria.conversation.infrastructure.observability;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * DIT 三层意图分类的单次分类明细。
 *
 * <p>每完成一次 {@code MultiHybridIntentService.doClassify}，异步写入一行，
 * 作为管理台命中率/延迟报表的唯一历史数据源（Micrometer Counter 进程重启即清零，
 * 无法承担历史趋势职责）。
 */
@Data
@Builder
@TableName("cs_conversation.cs_intent_tier_stat")
public class IntentTierStatEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String domainCode;

    /** 最终到达层：RULE / EMBEDDING / LLM */
    private String reachedTier;

    private Boolean tier1Hit;
    private Boolean tier2Executed;
    private Boolean tier2Hit;
    private Boolean tier3Executed;
    private Boolean tier3Hit;

    private Integer tier1LatencyMs;
    private Integer tier2LatencyMs;
    private Integer tier3LatencyMs;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
