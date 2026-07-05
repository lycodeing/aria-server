package com.aria.auth.interfaces.rest.vo;

/**
 * 通知渠道配置 VO。
 */
public record NotificationChannelsVO(
        String emailSmtp,
        Boolean emailEnabled,
        String webhookUrl,
        Boolean webhookEnabled
) {
}
