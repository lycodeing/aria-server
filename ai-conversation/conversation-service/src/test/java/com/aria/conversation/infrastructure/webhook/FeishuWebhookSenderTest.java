package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuWebhookSenderTest {

    FeishuWebhookSender sender;

    @BeforeEach
    void setUp() { sender = new FeishuWebhookSender(); }

    @Test
    @DisplayName("supportedType 返回 FEISHU")
    void supportedType_isFeishu() {
        assertThat(sender.supportedType()).isEqualTo("FEISHU");
    }

    @Test
    @DisplayName("buildRequestBody 包含会话ID和违规类型")
    void buildRequestBody_containsSessionAndType() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .url("https://example.com").build();
        SlaBreachEntity breach = SlaBreachEntity.builder()
                .sessionId("sess-001").breachType("WAIT").stage("BREACH")
                .targetSec(120).actualSec(185).build();
        SlaBreachContext ctx = new SlaBreachContext("sess-001", "张三", "VIP-SLA", List.of(breach));

        String body = sender.buildRequestBody(config, ctx);

        assertThat(body).contains("sess-001");
        assertThat(body).contains("排队等待超时");
    }
}
