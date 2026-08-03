package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEventContextTest {

    @Test
    @DisplayName("buildSlaBreach 填充 scope/eventType/sessionId/payload")
    void buildSlaBreach_fillsAllFields() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("sess-1");
        session.setVisitorName("张三");

        SlaPolicyEntity policy = new SlaPolicyEntity();
        policy.setName("默认 SLA");

        SlaBreachEntity breach = SlaBreachEntity.builder()
                .id(10L)
                .sessionId("sess-1")
                .build();
        breach.setBreachType(com.aria.conversation.domain.model.BreachType.WAIT);

        WebhookEventContext ctx = WebhookEventContextFactory.buildSlaBreach(
                session, policy, List.of(breach));

        assertThat(ctx.getScope()).isEqualTo(WebhookScope.SLA_BREACH);
        assertThat(ctx.getEventType()).isEqualTo("WAIT");
        assertThat(ctx.getSessionId()).isEqualTo("sess-1");
        assertThat(ctx.getVisitorName()).isEqualTo("张三");
        assertThat(ctx.getPayload())
                .containsEntry("policyName", "默认 SLA")
                .containsEntry("breachIds", List.of(10L));
    }

    @Test
    @DisplayName("WebhookEventContext builder 可构建任意事件上下文")
    void builder_buildsGenericContext() {
        WebhookEventContext ctx = WebhookEventContext.builder()
                .scope(WebhookScope.SESSION_CLOSED)
                .eventType("CLOSED")
                .sessionId("sess-2")
                .build();
        assertThat(ctx.getScope()).isEqualTo(WebhookScope.SESSION_CLOSED);
        assertThat(ctx.getEventType()).isEqualTo("CLOSED");
    }
}
