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
 * LLM Token 消耗日志。
 *
 * <p>每次 LLM 调用完成后异步写入一行，记录模型名、调用类型与 token 用量，
 * 作为管理台成本报表（按天/按模型聚合 Token 消耗）的数据源。
 */
@Data
@Builder
@TableName("cs_conversation.cs_llm_cost_log")
public class LlmCostLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 ID，可为 null（意图分类等系统级调用无会话上下文） */
    private String sessionId;

    /** 实际调用的模型名称（取自活跃 AiModelConfig.modelName） */
    private String modelName;

    /** 调用类型：CHAT | INTENT_CLASSIFY */
    private String callType;

    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;

    /** 本次调用耗时（毫秒） */
    private Integer latencyMs;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
