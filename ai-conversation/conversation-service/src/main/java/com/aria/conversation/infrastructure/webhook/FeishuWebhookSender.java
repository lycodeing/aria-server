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
    public String supportedType() {
        return "FEISHU";
    }

    @Override
    public void send(WebhookConfigEntity config, WebhookEventContext ctx) {
        String body = buildRequestBody(config, ctx);
        String url = config.getUrl();

        Map<String, String> headers = Map.of();
        if (config.getSecret() != null && !config.getSecret().isBlank()) {
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = sign(timestamp, config.getSecret());
            // 飞书签名通过 JSON body 携带，不通过 header
            body = injectSignature(body, timestamp, sign);
        }
        doPost(url, headers, body);
    }

    /**
     * 构造请求体（供测试调用）
     */
    String buildRequestBody(WebhookConfigEntity config, WebhookEventContext ctx) {
        Map<String, String> vars = buildVariables(ctx);

        if (config.getMessageTemplate() != null && !config.getMessageTemplate().isBlank()) {
            String rendered = renderTemplate(config.getMessageTemplate(), vars);
            // 向后兼容：若模板本身是平台 JSON 则原样发送
            if (isRawJson(rendered)) {
                return rendered;
            }
            // 否则视为 Markdown 内容，包装为飞书交互式卡片 JSON 2.0（支持完整 Markdown 语法）
            return wrapFeishuCard(rendered, ctx.getScope());
        }
        // 默认模板：WebhookDefaultTemplate 按 scope 提供 Markdown，包装为飞书卡片
        return wrapFeishuCard(WebhookDefaultTemplate.text(ctx.getScope(), vars), ctx.getScope());
    }

    /**
     * 将 Markdown 内容包装为飞书交互式卡片 JSON 2.0（彩色标题栏 + Markdown 正文）。
     *
     * @param markdownBody Markdown 正文内容
     * @param scope        事件范围（决定标题栏颜色与标题文字）
     */
    private String wrapFeishuCard(String markdownBody, com.aria.conversation.domain.model.WebhookScope scope) {
        String title = cardTitle(scope);
        String templateColor = cardColor(scope);
        return """
                {
                  "msg_type": "interactive",
                  "card": {
                    "schema": "2.0",
                    "header": {
                      "title": { "tag": "plain_text", "content": "%s" },
                      "template": "%s"
                    },
                    "body": {
                      "direction": "vertical",
                      "elements": [
                        { "tag": "markdown", "content": "%s" }
                      ]
                    }
                  }
                }
                """.formatted(escapeJson(title), templateColor, escapeJson(markdownBody));
    }

    /** 各事件范围的卡片标题 */
    private static String cardTitle(com.aria.conversation.domain.model.WebhookScope scope) {
        return switch (scope) {
            case SLA_BREACH         -> "⚠️ SLA 违规告警";
            case SESSION_CREATED    -> "📢 新会话";
            case SESSION_TRANSFERRED -> "🔄 转人工";
            case SESSION_CLOSED     -> "🔒 会话关闭";
            case CSAT_RATED         -> "⭐ 客户评价";
        };
    }

    /** 各事件范围的标题栏主题色 */
    private static String cardColor(com.aria.conversation.domain.model.WebhookScope scope) {
        return switch (scope) {
            case SLA_BREACH         -> "red";      // 红色告警
            case SESSION_CREATED    -> "blue";     // 蓝色信息
            case SESSION_TRANSFERRED -> "orange";   // 橙色提醒
            case SESSION_CLOSED     -> "grey";     // 灰色通知
            case CSAT_RATED         -> "turquoise"; // 青色评价
        };
    }

    private String sign(long timestamp, String secret) {
        try {
            // 飞书签名算法：HMAC-SHA256(key=timestamp+"\n"+secret, msg="")
            String signKey = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(new byte[0]);
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("飞书签名失败", e);
        }
    }

    private String injectSignature(String body, long timestamp, String sign) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> node = mapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
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
