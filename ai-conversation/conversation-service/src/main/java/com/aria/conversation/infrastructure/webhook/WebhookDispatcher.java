package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Webhook 分发器。
 *
 * <p>根据 Webhook 配置的 type 字段路由到对应 {@link WebhookSender} 实现，
 * 通过 {@code @Async("webhookExecutor")} 在独立线程池中执行，
 * 不阻塞 SLA 扫描主线程。
 *
 * <p>失败时记录 ERROR 日志但不重抛异常（重试逻辑由调用方决策）。
 */
@Slf4j
@Component
public class WebhookDispatcher {

    private final Map<String, WebhookSender>  senders;
    private final WebhookConfigMapper          webhookConfigMapper;
    private final SlaBreachMapper              slaBreachMapper;

    /** Spring 自动注入所有 WebhookSender 实现，按 supportedType() 建立路由表 */
    public WebhookDispatcher(List<WebhookSender> senderList,
                              WebhookConfigMapper webhookConfigMapper,
                              SlaBreachMapper slaBreachMapper) {
        this.senders = senderList.stream()
                .collect(Collectors.toMap(WebhookSender::supportedType, Function.identity()));
        this.webhookConfigMapper = webhookConfigMapper;
        this.slaBreachMapper     = slaBreachMapper;
    }

    /**
     * 异步分发 Webhook 通知。
     *
     * @param webhookIds 需要推送的 Webhook 配置 ID 列表
     * @param ctx        SLA 违规现场上下文
     * @param breachIds  本次违规记录 ID 列表（推送成功后更新 webhook_notified_at）
     */
    @Async("webhookExecutor")
    public void dispatch(List<Long> webhookIds, SlaBreachContext ctx, List<Long> breachIds) {
        if (webhookIds == null || webhookIds.isEmpty()) return;

        List<WebhookConfigEntity> configs =
                webhookConfigMapper.selectEnabledByIds(webhookIds);

        boolean anySuccess = false;
        for (WebhookConfigEntity config : configs) {
            WebhookSender sender = senders.get(config.getType());
            if (sender == null) {
                log.warn("[Webhook] 未找到类型 {} 的 Sender，跳过 id={}", config.getType(), config.getId());
                continue;
            }
            try {
                sendWithRetry(sender, config, ctx);
                anySuccess = true;
                log.info("[Webhook] 推送成功 id={} type={} session={}",
                         config.getId(), config.getType(), ctx.sessionId());
            } catch (Exception e) {
                log.error("[Webhook] 推送失败 id={} type={} session={}",
                          config.getId(), config.getType(), ctx.sessionId(), e);
            }
        }

        // 至少一个成功时才标记通知时间
        if (anySuccess && !breachIds.isEmpty()) {
            slaBreachMapper.updateWebhookNotifiedAt(breachIds, OffsetDateTime.now());
        }
    }

    /**
     * 带重试的发送（指数退避：1s / 3s / 9s，最多 3 次）。
     */
    private void sendWithRetry(WebhookSender sender, WebhookConfigEntity config,
                                SlaBreachContext ctx) {
        Exception lastEx = null;
        long delaySec = 1;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                sender.send(config, ctx);
                return;  // 成功直接返回
            } catch (Exception e) {
                lastEx = e;
                log.warn("[Webhook] 第 {}/3 次发送失败 id={}: {}", attempt, config.getId(), e.getMessage());
                if (attempt < 3) {
                    try { Thread.sleep(delaySec * 1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted during retry", ie);
                    }
                    delaySec *= 3;
                }
            }
        }
        throw new RuntimeException("Webhook 重试 3 次全部失败", lastEx);
    }
}
