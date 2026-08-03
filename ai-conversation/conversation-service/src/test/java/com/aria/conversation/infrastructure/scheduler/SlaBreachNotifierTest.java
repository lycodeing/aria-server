package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.domain.model.SlaBreachActions;
import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.webhook.WebhookEventContext;
import com.aria.conversation.infrastructure.webhook.WebhookEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SlaBreachNotifierTest {

    @Mock RabbitTemplate            eventsRabbitTemplate;
    @Mock ApplicationEventPublisher springEventPublisher;
    @Mock SlaBreachRecorder         recorder;
    @Mock WebhookEventPublisher     webhookEventPublisher;

    @Test
    @DisplayName("违规通知发布 SLA_BREACH 事件")
    void notifyBatch_publishesSlaBreach() {
        SlaBreachNotifier notifier = new SlaBreachNotifier(
                "cs.conversation.events", eventsRabbitTemplate,
                springEventPublisher, recorder, webhookEventPublisher);

        ConversationEntity session = new ConversationEntity();
        session.setSessionId("sess-1");
        session.setVisitorName("张三");

        SlaPolicyEntity policy = new SlaPolicyEntity();
        policy.setName("默认 SLA");
        SlaBreachActions actions = new SlaBreachActions();
        policy.setActions(actions);

        SlaBreachEntity breach = SlaBreachEntity.builder().id(10L).build();
        breach.setBreachType(BreachType.WAIT);
        breach.setStage(BreachStage.WARNING);

        notifier.notifyBatch(List.of(breach), policy, session);

        verify(webhookEventPublisher).publish(
                eq(WebhookScope.SLA_BREACH), any(WebhookEventContext.class));
    }
}
