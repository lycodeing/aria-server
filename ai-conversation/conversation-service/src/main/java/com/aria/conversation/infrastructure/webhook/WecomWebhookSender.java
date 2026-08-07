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
    public void send(WebhookConfigEntity config, WebhookEventContext ctx) {
        Map<String, String> vars = buildVariables(ctx);
        String body;
        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            String template = config.getMessageTemplate();
            if (isRawJson(template)) {
                // 向后兼容：用户手写的平台 JSON；变量值经 JSON 转义注入，防注入
                body = renderJsonTemplate(template, vars);
            } else {
                String rendered = renderTemplate(template, vars);
                body = """
                        {
                          "msgtype": "markdown",
                          "markdown": {
                            "content": "%s"
                          }
                        }
                        """.formatted(escapeJson(rendered));
            }
        } else {
            body = """
                    {
                      "msgtype": "markdown",
                      "markdown": {
                        "content": "%s"
                      }
                    }
                    """.formatted(escapeJson(WebhookDefaultTemplate.text(ctx.getScope(), vars)));
        }
        doPost(config.getUrl(), Map.of(), body);
    }
}
