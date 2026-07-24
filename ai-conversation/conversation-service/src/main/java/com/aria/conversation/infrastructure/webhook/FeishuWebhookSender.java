package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书 Webhook 发送器。
 * 签名算法：HMAC-SHA256(timestamp + "\n" + secret)，Base64 编码。
 * 消息格式：interactive 卡片或默认 text 消息。
 */
@Slf4j
@Component
public class FeishuWebhookSender extends AbstractWebhookSender {

    @Override
    public String supportedType() { return "FEISHU"; }

    @Override
    public void send(WebhookConfigEntity config, SlaBreachContext ctx) {
        String body = buildRequestBody(config, ctx);
        String url  = config.getUrl();

        Map<String, String> headers = Map.of();
        if (config.getSecret() != null && !config.getSecret().isBlank()) {
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = sign(timestamp, config.getSecret());
            // 飞书签名通过 JSON body 携带，不通过 header
            body = injectSignature(body, timestamp, sign);
        }
        doPost(url, headers, body);
    }

    /** 构造请求体（供测试调用） */
    String buildRequestBody(WebhookConfigEntity config, SlaBreachContext ctx) {
        Map<String, String> vars = buildVariables(ctx);

        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            return renderTemplate(config.getMessageTemplate(), vars);
        }
        // 默认飞书 text 消息
        return """
                {
                  "msg_type": "text",
                  "content": {
                    "text": "⚠️ SLA %s 违规\\n会话：%s\\n访客：%s\\n策略：%s\\n目标：%ss｜实际：%ss"
                  }
                }
                """.formatted(
                vars.get("breachTypeLabel"), vars.get("sessionId"),
                vars.get("visitorName"), vars.get("policyName"),
                vars.get("targetSec"), vars.get("actualSec"));
    }

    private String sign(long timestamp, String secret) {
        try {
            String content = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("飞书签名失败", e);
        }
    }

    private String injectSignature(String body, long timestamp, String sign) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> node = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            // Feishu requires timestamp and sign at root level, prepended before other fields
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timestamp", String.valueOf(timestamp));
            result.put("sign", sign);
            result.putAll(node);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("飞书签名注入失败: " + e.getMessage(), e);
        }
    }
}
