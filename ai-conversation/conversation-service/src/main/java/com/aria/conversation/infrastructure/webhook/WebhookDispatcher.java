package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Webhook 分发器。
 *
 * <p>根据 Webhook 配置的 type 字段路由到对应 {@link WebhookSender} 实现，
 * 通过 {@code @Async("webhookExecutor")} 在独立线程池中执行，
 * 不阻塞业务主线程。
 *
 * <p>通用分发器：不感知任何业务 scope。发送成功后调用
 * {@link WebhookEventContext#getOnSuccess()} 回调（如 SLA 违规回写 notified），
 * 失败不调用且不抛异常（重试逻辑由调用方决策）。
 */
@Slf4j
@Component
public class WebhookDispatcher {

    private static final int MAX_RETRY_ATTEMPTS      = 3;
    private static final int RETRY_BACKOFF_MULTIPLIER = 3;

    private final Map<String, WebhookSender>  senders;
    private final long                        retryBaseMs;

    /** Spring 自动注入所有 WebhookSender 实现，按 supportedType() 建立路由表 */
    public WebhookDispatcher(List<WebhookSender> senderList,
                              @Value("${sla.webhook.retry-base-ms:1000}") long retryBaseMs) {
        this.senders = senderList.stream()
                .collect(Collectors.toMap(WebhookSender::supportedType, Function.identity()));
        this.retryBaseMs = retryBaseMs;
    }

    /**
     * 异步分发单条 Webhook 通知（webhookExecutor 线程池）。
     *
     * <p>通用分发器：不感知任何业务 scope。发送成功后调用
     * {@link WebhookEventContext#getOnSuccess()} 回调（如 SLA 违规回写 notified），
     * 失败不调用且不抛异常。
     *
     * @param webhook 目标 Webhook 配置（已启用）
     * @param ctx     事件上下文
     */
    @Async("webhookExecutor")
    public void dispatch(WebhookConfigEntity webhook, WebhookEventContext ctx) {
        if (webhook == null) return;
        WebhookSender sender = senders.get(webhook.getType());
        if (sender == null) {
            log.warn("[Webhook] 未找到类型 {} 的 Sender，跳过 id={}", webhook.getType(), webhook.getId());
            return;
        }
        try {
            sendWithRetry(sender, webhook, ctx);
            log.info("[Webhook] 推送成功 id={} type={} scope={} session={}",
                     webhook.getId(), webhook.getType(), ctx.getScope(), ctx.getSessionId());
            if (ctx.getOnSuccess() != null) {
                ctx.getOnSuccess().run();
            }
        } catch (Exception e) {
            log.error("[Webhook] 推送失败 id={} type={} scope={} session={}",
                      webhook.getId(), webhook.getType(), ctx.getScope(), ctx.getSessionId(), e);
        }
    }

    /**
     * 带重试的发送（指数退避：1s / 3s / 9s，最多 MAX_RETRY_ATTEMPTS 次）。
     */
    private void sendWithRetry(WebhookSender sender, WebhookConfigEntity config,
                                WebhookEventContext ctx) {
        Exception lastEx = null;
        long delayFactor = 1;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                sender.send(config, ctx);
                return;  // 成功直接返回
            } catch (Exception e) {
                lastEx = e;
                log.warn("[Webhook] 第 {}/{} 次发送失败 id={}: {}", attempt, MAX_RETRY_ATTEMPTS, config.getId(), e.getMessage());
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try { Thread.sleep(delayFactor * retryBaseMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted during retry", ie);
                    }
                    delayFactor *= RETRY_BACKOFF_MULTIPLIER;
                }
            }
        }
        throw new RuntimeException("Webhook 重试 " + MAX_RETRY_ATTEMPTS + " 次全部失败", lastEx);
    }
}
