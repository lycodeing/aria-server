package com.aria.conversation.domain.model;

/**
 * Webhook 事件范围枚举。
 * webhook 配置通过 scopes 字段声明订阅哪些事件；业务事件按 scope 自动匹配推送。
 */
public enum WebhookScope {
    /** SLA 违规告警（含 WARNING 预警 / BREACH 正式违规两阶段） */
    SLA_BREACH,
    /** 新会话创建（访客进线） */
    SESSION_CREATED,
    /** 转人工 / 座席间转接 */
    SESSION_TRANSFERRED,
    /** 会话关闭 */
    SESSION_CLOSED,
    /** 客户提交评价 */
    CSAT_RATED
}
