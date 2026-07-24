package com.aria.conversation.application.service;

/**
 * Webhook 测试发送端口接口（application 层）。
 * 由 infrastructure 层的 WebhookTestSender 实现。
 */
public interface IWebhookSendService {
    /** 向指定 Webhook 配置发送一条模拟测试消息 */
    void sendTest(Long webhookId);
}
