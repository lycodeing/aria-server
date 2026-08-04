package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 自定义 Webhook 发送器。
 * 用户自定义请求头和消息模板，不内置签名逻辑。
 */
@Slf4j
@Component
public class CustomWebhookSender extends AbstractWebhookSender {

    @Override
    public String supportedType() { return "CUSTOM"; }

    @Override
    public void send(WebhookConfigEntity config, WebhookEventContext ctx) {
        String body = buildRequestBody(config, ctx);

        Map<String, String> headers = new HashMap<>();
        if (config.getCustomHeaders() != null) {
            config.getCustomHeaders().forEach(headers::put);
        }
        doPost(config.getUrl(), headers, body);
    }

    /** 构造请求体（供测试调用） */
    String buildRequestBody(WebhookConfigEntity config, WebhookEventContext ctx) {
        Map<String, String> vars = buildVariables(ctx);
        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            String rendered = renderTemplate(config.getMessageTemplate(), vars);
            if (isRawJson(rendered)) {
                return rendered; // 向后兼容：用户手写的原始 JSON 请求体
            }
            return "{\"message\":\"" + escapeJson(rendered) + "\"}";
        }
        return "{\"message\":\"" + escapeJson(WebhookDefaultTemplate.text(ctx.getScope(), vars)) + "\"}";
    }
}
