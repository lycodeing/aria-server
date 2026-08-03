package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CustomWebhookSenderTest {

    CustomWebhookSender sender;

    @BeforeEach
    void setUp() { sender = new CustomWebhookSender(); }

    @Test
    @DisplayName("supportedType 返回 CUSTOM")
    void supportedType_isCustom() {
        assertThat(sender.supportedType()).isEqualTo("CUSTOM");
    }

    @Test
    @DisplayName("默认模板按 scope 渲染，SESSION_CLOSED 无未解析占位符且为合法 JSON")
    void buildRequestBody_defaultTemplate_scopeAwareAndValidJson() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .url("https://example.com").build();
        WebhookEventContext ctx = WebhookEventContextFactory.buildSessionEvent(
                WebhookScope.SESSION_CLOSED, "CLOSED", "sess-2", null, Map.of());

        String body = sender.buildRequestBody(config, ctx);

        assertThat(body).doesNotContain("${");
        assertThat(body).contains("sess-2");
        assertThatCode(() -> new ObjectMapper().readTree(body)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("自定义模板渲染变量")
    void buildRequestBody_customTemplate_rendersVariables() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .url("https://example.com")
                .messageTemplate("{\"msg\":\"会话 ${sessionId} 结束\"}")
                .build();
        WebhookEventContext ctx = WebhookEventContextFactory.buildSessionEvent(
                WebhookScope.SESSION_CLOSED, "CLOSED", "sess-2", null, Map.of());

        String body = sender.buildRequestBody(config, ctx);

        assertThat(body).contains("sess-2").doesNotContain("${");
    }
}
