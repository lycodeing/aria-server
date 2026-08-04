package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;

import java.util.Map;

/**
 * 按 scope 分类的默认消息模板提供者。
 *
 * <p>各 Sender（Feishu/Dingtalk/Wecom）在无自定义 messageTemplate 时调用
 * {@link #text(WebhookScope, Map)}，避免 3 个 Sender 各自内联 scope 分支导致模板重复。
 * 占位符语法统一为 {@code ${var}}（与 AbstractWebhookSender.renderTemplate 一致）。
 */
public final class WebhookDefaultTemplate {

    private WebhookDefaultTemplate() {}

    /** 返回 scope 的默认 Markdown 文本（各 Sender 负责包装成平台 JSON）。 */
    public static String text(WebhookScope scope, Map<String, String> vars) {
        return switch (scope) {
            case SLA_BREACH -> """
                    ⚠️ **SLA 违规告警**

                    ---

                    **会话ID**：%s
                    **访客**：%s
                    **策略**：%s

                    ---

                    **违规类型**：%s
                    **目标**：%ss ｜ **实际**：%ss""".formatted(
                    vars.getOrDefault("sessionId", ""),
                    vars.getOrDefault("visitorName", "未知访客"),
                    vars.getOrDefault("policyName", ""),
                    vars.getOrDefault("breachTypeLabel", ""),
                    vars.getOrDefault("targetSec", ""),
                    vars.getOrDefault("actualSec", ""));
            case SESSION_CREATED -> """
                    📢 **新会话**

                    **访客**：%s
                    **会话ID**：%s""".formatted(
                    vars.getOrDefault("visitorName", "未知访客"),
                    vars.getOrDefault("sessionId", ""));
            case SESSION_TRANSFERRED -> """
                    🔄 **转人工**

                    **访客**：%s
                    **会话ID**：%s""".formatted(
                    vars.getOrDefault("visitorName", "未知访客"),
                    vars.getOrDefault("sessionId", ""));
            case SESSION_CLOSED -> """
                    🔒 **会话关闭**

                    **会话ID**：%s""".formatted(
                    vars.getOrDefault("sessionId", ""));
            case CSAT_RATED -> """
                    ⭐ **客户评价**

                    **会话ID**：%s
                    **评分**：%s 星
                    **评价**：%s""".formatted(
                    vars.getOrDefault("sessionId", ""),
                    vars.getOrDefault("score", ""),
                    vars.getOrDefault("comment", ""));
        };
    }
}
