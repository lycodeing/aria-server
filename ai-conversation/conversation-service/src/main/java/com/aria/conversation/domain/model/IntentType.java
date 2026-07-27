package com.aria.conversation.domain.model;

/**
 * 用户意图枚举。
 *
 * <p>LLM 意图分类器返回这些值之一，主流程根据此值决定路由。
 *
 * <ul>
 *   <li>{@link #FAQ_QUERY}        — 知识问答，走 RAG + LLM 正常流程</li>
 *   <li>{@link #TRANSFER_REQUEST} — 用户明确/隐含要求转人工，自动入队</li>
 *   <li>{@link #COMPLAINT}        — 投诉，视为高优先级，自动转人工</li>
 *   <li>{@link #CHITCHAT}         — 闲聊/问候，跳过 RAG 直接 LLM 回复</li>
 *   <li>{@link #OUT_OF_SCOPE}     — 与业务完全无关，返回拒答模板</li>
 *   <li>{@link #UNKNOWN}          — 分类失败兜底，走 FAQ_QUERY 流程</li>
 * </ul>
 */
public enum IntentType {
    FAQ_QUERY,
    TRANSFER_REQUEST,
    COMPLAINT,
    CHITCHAT,
    OUT_OF_SCOPE,
    UNKNOWN;

    /**
     * 从业务意图 code 字符串安全解析枚举值（LLM 输出解析语境）。
     *
     * <p>适用于解析 LLM 返回的意图 code，未知 code 视为幻觉返回 {@link #UNKNOWN}，
     * 由上层路由逻辑决定如何降级处理。
     *
     * @param code 意图 code（大小写不敏感）
     * @return 对应枚举值，null/blank/未知时返回 {@link #UNKNOWN}
     * @see #fromBusinessCode(String) 业务路由语境（Tier1/Tier2）使用此方法
     */
    public static IntentType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return UNKNOWN;
        }
        String upper = code.toUpperCase();
        for (IntentType t : values()) {
            if (t.name().equals(upper)) {
                return t;
            }
        }
        // 未知 code 视为 LLM 幻觉，回退到 UNKNOWN，由路由层做降级决策
        return UNKNOWN;
    }

    /**
     * 从业务意图 code 字符串安全解析枚举值（业务路由语境，Tier1/Tier2 使用）。
     *
     * <p>适用于 {@link com.aria.conversation.infrastructure.dit.config.IntentConfig#code()}
     * 等业务配置的意图 code，这些 code 可能不对应枚举名（如自定义业务意图）。
     * 未知 code 映射为 {@link #FAQ_QUERY}，表示走通用 RAG + LLM 处理路径。
     *
     * @param code 业务意图 code（大小写不敏感）
     * @return 对应枚举值，未知时返回 {@link #FAQ_QUERY}
     * @see #fromCode(String) LLM 输出解析语境使用此方法
     */
    public static IntentType fromBusinessCode(String code) {
        if (code == null || code.isBlank()) {
            return FAQ_QUERY;
        }
        String upper = code.toUpperCase();
        for (IntentType t : values()) {
            if (t.name().equals(upper)) {
                return t;
            }
        }
        // 自定义业务意图 code（如 "query_order"）不在枚举内，走 FAQ_QUERY（RAG + LLM）路径
        return FAQ_QUERY;
    }
}
