package com.aria.conversation.application.service;

import com.aria.conversation.domain.model.event.SlaEscalationRequestedEvent;
import com.aria.conversation.infrastructure.scheduler.SlaBreachRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * SLA 自动升级事件处理器（application 层）。
 *
 * <p>响应 {@link SlaEscalationRequestedEvent}，调用 {@link SessionQueueService#transfer}
 * 将会话转交给配置的目标坐席。
 *
 * <p>与 SlaBreachNotifier（infrastructure）通过领域事件解耦，避免跨聚合直接调用。
 *
 * <p>注意：{@link SessionQueueService#transfer} 要求 fromAgentId 不为 null 且格式合法。
 * 若会话当前无坐席（WAITING 状态或 Redis 数据缺失），本次升级将被跳过并记录 warn 日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaEscalationHandler {

    private final SessionQueueService sessionQueueService;
    private final SlaBreachRecorder   recorder;

    @EventListener
    public void onEscalationRequested(SlaEscalationRequestedEvent event) {
        try {
            // transfer() 需要 fromAgentId，先从队列中查出当前负责的坐席
            String fromAgentId = sessionQueueService.getAgentId(event.sessionId());
            if (fromAgentId == null || fromAgentId.isBlank()) {
                log.warn("[SLA] autoEscalate skipped — no active agent for session={} target={}",
                        event.sessionId(), event.targetAgentId());
                return;
            }

            sessionQueueService.transfer(event.sessionId(), fromAgentId, event.targetAgentId());

            OffsetDateTime now = OffsetDateTime.now();
            event.breachIds().forEach(id -> recorder.markEscalated(id, now));
        } catch (Exception e) {
            log.warn("[SLA] autoEscalate failed session={} target={}",
                    event.sessionId(), event.targetAgentId(), e);
        }
    }
}
