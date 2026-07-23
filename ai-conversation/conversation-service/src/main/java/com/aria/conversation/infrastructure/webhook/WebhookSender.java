package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;

/**
 * Webhook 发送器接口（Strategy 模式）。
 * 每个平台实现一个，由 WebhookDispatcher 根据 config.type 路由。
 */
public interface WebhookSender {

    /** 返回支持的平台类型字符串，如 "FEISHU"，与 WebhookConfigEntity.type 匹配 */
    String supportedType();

    /**
     * 发送 Webhook 通知。实现类负责签名、序列化和 HTTP 发送。
     * 失败时抛出 RuntimeException，由 WebhookDispatcher 统一处理重试。
     */
    void send(WebhookConfigEntity config, SlaBreachContext ctx);
}
