package com.aria.conversation.infrastructure.webhook;

/**
 * Webhook 事件细化类型常量，集中定义避免各处魔法字符串。
 */
public final class WebhookEventTypes {

    private WebhookEventTypes() {}

    // SLA 违规（eventType 复用 BreachType：WAIT/FRT/HANDLE）
    // 会话生命周期
    public static final String SESSION_CREATED = "CREATED";
    public static final String SESSION_ENQUEUE = "ENQUEUE";
    public static final String SESSION_TRANSFER = "TRANSFER";
    public static final String SESSION_CLOSED = "CLOSED";
    // 客户评价
    public static final String CSAT_RATED = "RATED";
}
