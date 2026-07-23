package com.aria.conversation.domain.model;

import lombok.Data;

import java.util.List;

/**
 * SLA 违规行为配置（对应 cs_sla_policy.actions JSON 字段）。
 *
 * <p>使用 MyBatis-Plus {@code JacksonTypeHandler} 反序列化，
 * 因此需要无参构造 + setter（由 {@code @Data} 提供）。
 */
@Data
public class SlaBreachActions {

    /** 仅记录违规，不做额外动作（默认开启） */
    private boolean recordBreachOnly = true;

    /** 通过 SSE 向座席推送违规告警（默认开启） */
    private boolean sseAlert = true;

    /** 是否自动升级给其他座席（默认关闭） */
    private boolean autoEscalate = false;

    /** 自动升级的目标座席 ID（autoEscalate=true 时有效） */
    private String escalateToUserId;

    /** 违规时推送的 Webhook 配置 ID 列表，空列表表示不推送 */
    private List<Long> webhookIds;
}
