package com.aria.conversation.infrastructure.ai;

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
    UNKNOWN
}
