package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;

import lombok.extern.slf4j.Slf4j;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class AbstractWebhookSender implements WebhookSender {

    // TODO: 可通过 sla.webhook.connect-timeout-ms / sla.webhook.read-timeout-ms 外部化配置
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 10_000;

    protected static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    /**
     * 执行 HTTP POST 请求。
     *
     * @param url     请求 URL
     * @param headers 请求头 Map（key=header名，value=header值）
     * @param body    JSON 请求体字符串
     * @throws RuntimeException 若 HTTP 状态码非 2xx 或请求超时
     */
    protected void doPost(String url, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .POST(HttpRequest.BodyPublishers.ofString(body));

        builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);

        log.info("[Webhook] 发送请求 url={} headers={} body={}", url, headers, body);
        try {
            HttpResponse<String> resp = HTTP_CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            log.info("[Webhook] 收到响应 status={} body={}", resp.statusCode(), resp.body());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("Webhook HTTP " + resp.statusCode()
                        + ": " + resp.body());
            }
            // 各平台即使出错也返回 HTTP 200，需检查响应体中的业务错误码
            checkPlatformError(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Webhook request interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Webhook request failed: " + e.getMessage(), e);
        }
    }

    /**
     * 检查各平台响应体中的业务错误。
     * 飞书/钉钉/企微在签名失败、URL 无效等情况下仍返回 HTTP 200，
     * 但响应 JSON 中 code != 0 表示失败。
     */
    private void checkPlatformError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            // 飞书/钉钉/企微统一用 "code" 字段，0 或不存在表示成功
            if (node.has("code") && node.get("code").asInt() != 0) {
                String msg = node.has("msg") ? node.get("msg").asText() : "未知错误";
                throw new RuntimeException("Webhook 平台返回错误: " + msg);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 非 JSON 响应体（如自定义 webhook），跳过检查
        }
    }

    /** 将模板中的 ${变量} 替换为实际值，并将字面 \n/\t 转为真换行/制表符 */
    protected String renderTemplate(String template,
                                     Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        // 数据库中存储的模板可能含字面 \n（反斜杠+n），转为真换行符以便后续 escapeJson 正确处理
        result = result.replace("\\n", "\n").replace("\\t", "\t");
        return result;
    }

    /** JSON 字符串转义：反斜杠、换行、双引号 */
    protected static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    /**
     * 判断渲染后的模板是否为原始 JSON（以 { 或 [ 开头）。
     * 若是则原样发送（向后兼容用户手写的平台 JSON），
     * 否则视为消息内容，由各 sender 包装为平台消息体。
     */
    protected static boolean isRawJson(String text) {
        String trimmed = text.stripLeading();
        return !trimmed.isEmpty() && (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[');
    }

    /** 从 WebhookEventContext 构造模板变量 Map（SLA 违规取第一条违规信息） */
    protected Map<String, String> buildVariables(WebhookEventContext ctx) {
        Map<String, String> vars = new HashMap<>();
        vars.put("sessionId",   ctx.getSessionId()   != null ? ctx.getSessionId()   : "");
        vars.put("visitorName", ctx.getVisitorName() != null ? ctx.getVisitorName() : "未知访客");
        vars.put("eventType",   ctx.getEventType()   != null ? ctx.getEventType()   : "");
        if (ctx.getPayload() != null) {
            ctx.getPayload().forEach((k, v) ->
                    vars.put(k, v == null ? "" : String.valueOf(v)));
        }
        if (ctx.getScope() == WebhookScope.SLA_BREACH && ctx.getPayload() != null) {
            Object breachesObj = ctx.getPayload().get("breaches");
            if (breachesObj instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof SlaBreachEntity breach) {
                BreachType type = breach.getBreachType();
                String label = type == null ? "" : switch (type) {
                    case WAIT   -> "排队等待超时";
                    case FRT    -> "首响超时";
                    case HANDLE -> "处理超时";
                };
                vars.put("breachType",      type != null ? type.getValue() : "");
                vars.put("breachTypeLabel", label);
                vars.put("targetSec",       breach.getTargetSec()  != null ? String.valueOf(breach.getTargetSec())  : "");
                vars.put("actualSec",       breach.getActualSec()  != null ? String.valueOf(breach.getActualSec())  : "");
                vars.put("breachAt",        breach.getBreachAt()   != null ? breach.getBreachAt().toString()       : "");
                vars.put("stage",           breach.getStage()      != null ? breach.getStage().getValue()          : "");
            }
        }
        return vars;
    }
}
