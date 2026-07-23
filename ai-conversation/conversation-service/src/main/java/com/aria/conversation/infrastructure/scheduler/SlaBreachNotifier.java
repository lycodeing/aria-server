package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.SessionEventType;
import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.SlaBreachActions;
import com.aria.conversation.domain.model.event.SlaEscalationRequestedEvent;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.webhook.SlaBreachContext;
import com.aria.conversation.infrastructure.webhook.WebhookDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * SLA 违规通知组件。
 *
 * <p>SSE 告警：将聚合事件发送到 fanout exchange，由 SessionEventSubscriber 广播给所有坐席。
 * <p>自动升级：发布 {@link SlaEscalationRequestedEvent} 领域事件（domain 层），
 * 由 application 层的 SlaEscalationHandler 订阅处理，避免跨聚合直接调用。
 *
 * <p>注意：本类在 @Transactional 边界内被调用，
 * Spring 事件默认同步分发（事务提交前），autoEscalate 失败时事务回滚可能产生幽灵通知。
 * 此为已知权衡，可接受。
 */
@Slf4j
@Component
public class SlaBreachNotifier {

    private final String eventsExchange;
    private final RabbitTemplate eventsRabbitTemplate;
    private final ApplicationEventPublisher springEventPublisher;
    private final SlaBreachRecorder recorder;
    private final WebhookDispatcher webhookDispatcher;

    public SlaBreachNotifier(
            @Value("${conversation.events.exchange}") String eventsExchange,
            @Qualifier("eventsRabbitTemplate") RabbitTemplate eventsRabbitTemplate,
            ApplicationEventPublisher springEventPublisher,
            SlaBreachRecorder recorder,
            WebhookDispatcher webhookDispatcher) {
        this.eventsExchange = eventsExchange;
        this.eventsRabbitTemplate = eventsRabbitTemplate;
        this.springEventPublisher = springEventPublisher;
        this.recorder = recorder;
        this.webhookDispatcher = webhookDispatcher;
    }

    /**
     * 批量通知：同一会话本轮所有新违规聚合为一条 SSE，避免告警疲劳。
     * autoEscalate 仅在有 BREACH 阶段记录时触发，WARNING 不触发升级。
     *
     * @param newBreaches 本轮新写入的违规实体列表（非空）
     * @param policy      匹配的 SLA 策略
     * @param session     会话实体
     */
    public void notifyBatch(List<SlaBreachEntity> newBreaches,
                            SlaPolicyEntity policy,
                            ConversationEntity session) {
        if (newBreaches == null || newBreaches.isEmpty()) {
            return;
        }

        SlaBreachActions actions = policy.getActions();
        OffsetDateTime now = OffsetDateTime.now();

        // SSE 聚合推送：将本轮所有违规合并为一条消息
        if (actions.isSseAlert()) {
            try {
                Map<String, Object> event = Map.of(
                        "type",        SessionEventType.SLA_BREACH.name(),
                        "sessionId",   session.getSessionId(),
                        "visitorName", session.getVisitorName() != null ? session.getVisitorName() : "",
                        "agentId",     session.getAgentId()     != null ? session.getAgentId()     : "",
                        "policyName",  policy.getName(),
                        "breaches",    newBreaches.stream().map(b -> Map.of(
                                "breachType", b.getBreachType(),
                                "stage",      b.getStage(),
                                "targetSec",  b.getTargetSec(),
                                "actualSec",  b.getActualSec()
                        )).toList()
                );
                eventsRabbitTemplate.convertAndSend(eventsExchange, "", event);
                newBreaches.forEach(b -> recorder.markAlerted(b.getId(), now));
            } catch (Exception e) {
                log.error("[SLA] SSE publish failed session={}", session.getSessionId(), e);
            }
        }

        // 自动升级：仅 BREACH 阶段触发，WARNING 不升级
        boolean hasActualBreach = newBreaches.stream()
                .anyMatch(b -> BreachStage.BREACH.name().equals(b.getStage()));
        if (hasActualBreach && actions.isAutoEscalate()
                && actions.getEscalateToUserId() != null) {
            List<Long> breachIds = newBreaches.stream()
                    .filter(b -> BreachStage.BREACH.name().equals(b.getStage()))
                    .map(SlaBreachEntity::getId)
                    .toList();
            springEventPublisher.publishEvent(
                    new SlaEscalationRequestedEvent(
                            session.getSessionId(),
                            actions.getEscalateToUserId(),
                            breachIds));
        }

        // Webhook 推送（异步，不阻塞主线程）
        List<Long> webhookIds = actions.getWebhookIds();
        if (webhookIds != null && !webhookIds.isEmpty()) {
            List<Long> allBreachIds = newBreaches.stream()
                    .map(SlaBreachEntity::getId).toList();
            SlaBreachContext webhookCtx = new SlaBreachContext(
                    session.getSessionId(),
                    session.getVisitorName(),
                    policy.getName(),
                    newBreaches);
            webhookDispatcher.dispatch(webhookIds, webhookCtx, allBreachIds);
        }
    }
}
