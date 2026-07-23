package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 企业微信 Webhook 发送器。
 * 企业微信无需签名，直接 POST 到 Webhook URL。
 */
@Slf4j
@Component
public class WecomWebhookSender extends AbstractWebhookSender {

    @Override
    public String supportedType() { return "WECOM"; }

    @Override
    public void send(WebhookConfigEntity config, SlaBreachContext ctx) {
        Map<String, String> vars = buildVariables(ctx);
        String body;
        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            body = renderTemplate(config.getMessageTemplate(), vars);
        } else {
            body = """
                    {
                      "msgtype": "markdown",
                      "markdown": {
                        "content": "## ⚠️ SLA %s 违规\\n> 会话：%s\\n> 访客：%s\\n> 策略：%s\\n> 目标：%ss / 实际：%ss"
                      }
                    }
                    """.formatted(
                    vars.get("breachTypeLabel"), vars.get("sessionId"),
                    vars.get("visitorName"), vars.get("policyName"),
                    vars.get("targetSec"), vars.get("actualSec"));
        }
        doPost(config.getUrl(), Map.of(), body);
    }
}
