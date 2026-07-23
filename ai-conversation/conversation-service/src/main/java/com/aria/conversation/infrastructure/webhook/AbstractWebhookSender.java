package com.aria.conversation.infrastructure.webhook;

import lombok.extern.slf4j.Slf4j;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Slf4j
public abstract class AbstractWebhookSender implements WebhookSender {

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

        try {
            HttpResponse<String> resp = HTTP_CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("Webhook HTTP " + resp.statusCode()
                        + ": " + resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Webhook request interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Webhook request failed: " + e.getMessage(), e);
        }
    }

    /** 将模板中的 ${变量} 替换为实际值 */
    protected String renderTemplate(String template,
                                     Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /** 从 SlaBreachContext 构造模板变量 Map（取第一条违规的信息） */
    protected Map<String, String> buildVariables(SlaBreachContext ctx) {
        if (ctx.breaches() == null || ctx.breaches().isEmpty()) {
            throw new IllegalArgumentException("SlaBreachContext breaches 列表不能为空");
        }
        var breach = ctx.breaches().get(0);
        String label = switch (breach.getBreachType()) {
            case "WAIT"   -> "排队等待超时";
            case "FRT"    -> "首响超时";
            case "HANDLE" -> "处理超时";
            default       -> breach.getBreachType();
        };
        return Map.of(
            "sessionId",       ctx.sessionId(),
            "visitorName",     ctx.visitorName() != null ? ctx.visitorName() : "未知访客",
            "breachType",      breach.getBreachType(),
            "breachTypeLabel", label,
            "targetSec",       String.valueOf(breach.getTargetSec()),
            "actualSec",       String.valueOf(breach.getActualSec()),
            "policyName",      ctx.policyName(),
            "breachAt",        breach.getBreachAt() != null
                               ? breach.getBreachAt().toString() : "",
            "stage",           breach.getStage()
        );
    }
}
