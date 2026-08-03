package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;

import java.util.List;
import java.util.Map;

/**
 * Webhook 事件上下文工厂：统一构造各事件类型的上下文，避免调用方重复拼装。
 * 所有 scope 的事件上下文均通过本工厂构造，事件类型使用 {@link WebhookEventTypes} 常量。
 */
public final class WebhookEventContextFactory {

    private WebhookEventContextFactory() {}

    /** 构造 SLA 违规事件上下文（payload 含 policyName/breaches/breachIds）。 */
    public static WebhookEventContext buildSlaBreach(ConversationEntity session,
                                                      SlaPolicyEntity policy,
                                                      List<SlaBreachEntity> breaches) {
        SlaBreachEntity first = breaches.get(0);
        return WebhookEventContext.builder()
                .scope(WebhookScope.SLA_BREACH)
                .eventType(first.getBreachType() != null ? first.getBreachType().getValue() : "")
                .sessionId(session.getSessionId())
                .visitorName(session.getVisitorName())
                .payload(Map.of(
                        "policyName", policy.getName(),
                        "breaches", breaches,
                        "breachIds", breaches.stream().map(SlaBreachEntity::getId).toList()))
                .build();
    }

    /** 构造会话生命周期事件上下文（SESSION_CREATED / SESSION_TRANSFERRED / SESSION_CLOSED）。 */
    public static WebhookEventContext buildSessionEvent(WebhookScope scope,
                                                        String eventType,
                                                        String sessionId,
                                                        String visitorName,
                                                        Map<String, Object> extra) {
        return WebhookEventContext.builder()
                .scope(scope)
                .eventType(eventType)
                .sessionId(sessionId)
                .visitorName(visitorName)
                .payload(extra)
                .build();
    }

    /** 构造客户评价事件上下文（payload 含 csatId/score/comment/channel）。 */
    public static WebhookEventContext buildCsatRated(String sessionId,
                                                     Long csatId,
                                                     Object score,
                                                     String comment,
                                                     String channel) {
        return WebhookEventContext.builder()
                .scope(WebhookScope.CSAT_RATED)
                .eventType(WebhookEventTypes.CSAT_RATED)
                .sessionId(sessionId)
                .payload(Map.of(
                        "csatId", csatId,
                        "score", score == null ? "" : score,
                        "comment", comment == null ? "" : comment,
                        "channel", channel == null ? "" : channel))
                .build();
    }
}
