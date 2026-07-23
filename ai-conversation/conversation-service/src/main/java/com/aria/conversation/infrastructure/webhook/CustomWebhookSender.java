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

    private static final String DEFAULT_TEMPLATE =
            "{\"message\":\"SLA ${breachTypeLabel} 违规，会话：${sessionId}，"
            + "目标：${targetSec}s，实际：${actualSec}s\"}";

    @Override
    public String supportedType() { return "CUSTOM"; }

    @Override
    public void send(WebhookConfigEntity config, SlaBreachContext ctx) {
        Map<String, String> vars = buildVariables(ctx);
        String template = (config.getMessageTemplate() != null
                && !config.getMessageTemplate().isBlank())
                ? config.getMessageTemplate() : DEFAULT_TEMPLATE;
        String body = renderTemplate(template, vars);

        Map<String, String> headers = new HashMap<>();
        if (config.getCustomHeaders() != null) {
            config.getCustomHeaders().forEach(headers::put);
        }
        doPost(config.getUrl(), headers, body);
    }
}
