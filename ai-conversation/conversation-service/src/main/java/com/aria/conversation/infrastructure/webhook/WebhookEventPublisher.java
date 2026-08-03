package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Webhook 事件发布器。
 *
 * <p>业务事件点调用 {@link #publish(WebhookScope, WebhookEventContext)}，
 * 内部查询订阅该 scope 的启用 webhook 并逐个异步分发：
 * <ol>
 *   <li>通过 {@link WebhookConfigMapper#selectEnabledByScope} 匹配订阅 webhook</li>
 *   <li>空集合直接返回（零开销）</li>
 *   <li>逐个提交给 {@link WebhookDispatcher} 异步发送（webhookExecutor 线程池）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventPublisher {

    private final WebhookConfigMapper webhookConfigMapper;
    private final WebhookDispatcher   webhookDispatcher;

    /**
     * 发布事件：查询订阅该 scope 的启用 webhook，逐个异步分发。
     *
     * <p><b>故障隔离（关键）</b>：整体 try/catch 包裹，任何异常（DB 查询失败、
     * dispatcher 异常）都只记 ERROR 日志后返回，绝不向上抛出——
     * 调用点位于 {@code CsatService.rate}（@Transactional）、
     * {@code SessionQueueService.enqueue/close/transfer}、
     * {@code VisitorSessionService.getOrCreate}（Redisson 锁内）等业务主流程，
     * 一旦抛出会导致评分事务回滚、会话关闭 500、锁内异常等连锁故障。
     * 保持"通知失败不影响主流程"语义。
     *
     * @param scope 事件范围（必须非 null）
     * @param ctx   事件上下文；若 ctx.scope 为 null 则以入参为准兜底赋值
     */
    public void publish(WebhookScope scope, WebhookEventContext ctx) {
        try {
            List<WebhookConfigEntity> targets = webhookConfigMapper.selectEnabledByScope(scope.name());
            if (targets.isEmpty()) {
                log.debug("[WebhookPublisher] scope={} 无匹配 webhook，跳过", scope);
                return;
            }
            if (ctx.getScope() == null) {
                ctx.setScope(scope); // 仅 null 时兜底，不静默覆盖调用方显式值
            }
            targets.forEach(webhook -> webhookDispatcher.dispatch(webhook, ctx));
            log.debug("[WebhookPublisher] scope={} 命中 {} 个 webhook", scope, targets.size());
        } catch (Exception e) {
            // 故障隔离：通知链路异常绝不回抛到业务主流程
            log.error("[WebhookPublisher] 发布失败 scope={} sessionId={}",
                    scope, ctx.getSessionId(), e);
        }
    }
}
