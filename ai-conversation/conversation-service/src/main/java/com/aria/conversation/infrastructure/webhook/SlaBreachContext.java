package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import java.util.List;

/**
 * SLA 违规现场上下文，供 WebhookSender 构造消息使用。
 */
public record SlaBreachContext(
        String sessionId,
        String visitorName,
        String policyName,
        List<SlaBreachEntity> breaches
) {}
