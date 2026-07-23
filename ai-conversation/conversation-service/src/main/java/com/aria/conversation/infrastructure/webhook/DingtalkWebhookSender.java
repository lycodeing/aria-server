package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 钉钉 Webhook 发送器。
 * 签名算法：HMAC-SHA256(timestamp + "\n" + secret)，
 * 签名和时间戳通过 URL 参数传递：?timestamp=xxx&sign=xxx
 */
@Slf4j
@Component
public class DingtalkWebhookSender extends AbstractWebhookSender {

    @Override
    public String supportedType() { return "DINGTALK"; }

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
                        "title": "SLA违规告警",
                        "text": "### ⚠️ SLA %s 违规\\n- 会话：%s\\n- 访客：%s\\n- 策略：%s\\n- 目标：%ss｜实际：%ss"
                      }
                    }
                    """.formatted(
                    vars.get("breachTypeLabel"), vars.get("sessionId"),
                    vars.get("visitorName"), vars.get("policyName"),
                    vars.get("targetSec"), vars.get("actualSec"));
        }

        String url = config.getUrl();
        if (config.getSecret() != null && !config.getSecret().isBlank()) {
            long timestamp = System.currentTimeMillis();
            String sign = sign(timestamp, config.getSecret());
            url += (url.contains("?") ? "&" : "?")
                    + "timestamp=" + timestamp
                    + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
        }
        doPost(url, Map.of(), body);
    }

    private String sign(long timestamp, String secret) {
        try {
            String content = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("钉钉签名失败", e);
        }
    }
}
