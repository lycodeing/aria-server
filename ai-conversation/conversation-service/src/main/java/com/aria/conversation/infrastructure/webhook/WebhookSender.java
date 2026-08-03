package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;

/**
 * Webhook 发送器 SPI。
 * 各实现按 {@link #supportedType()} 声明支持的渠道类型，
 * 由 WebhookDispatcher 按配置路由调用。
 */
public interface WebhookSender {

    /** 支持的渠道类型：FEISHU | DINGTALK | WECOM | CUSTOM */
    String supportedType();

    /**
     * 发送一条事件通知。
     *
     * @param config Webhook 配置（含 URL/签名密钥/模板）
     * @param ctx    事件上下文（scope/eventType/payload）
     * @throws RuntimeException 发送失败时抛出，由调用方决定重试
     */
    void send(WebhookConfigEntity config, WebhookEventContext ctx);
}
