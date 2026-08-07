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

    @Test
    @DisplayName("自定义 Markdown 模板包装为 {\"message\":\"...\"} JSON")
    void buildRequestBody_customMarkdownTemplate_wrapsAsMessageJson() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .url("https://example.com")
                .messageTemplate("### 告警\n会话：${sessionId}")
                .build();
        WebhookEventContext ctx = WebhookEventContextFactory.buildSessionEvent(
                WebhookScope.SESSION_CLOSED, "CLOSED", "sess-3", null, Map.of());

        String body = sender.buildRequestBody(config, ctx);

        assertThatCode(() -> new ObjectMapper().readTree(body)).doesNotThrowAnyException();
        assertThat(body).contains("sess-3");
        assertThat(body).contains("### 告警");
    }

    @Test
    @DisplayName("raw JSON 模板：访客昵称含回车/制表符/双引号仍产出合法 JSON（escapeJson 控制字符转义）")
    void buildRequestBody_rawJsonTemplate_visitorNameWithControlChars_staysValidJson() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .url("https://example.com")
                .messageTemplate("{\"msg\":\"访客 ${visitorName} 结束会话 ${sessionId}\"}")
                .build();
        // 访客昵称含回车、制表符、双引号——未转义会破坏 JSON 结构
        WebhookEventContext ctx = WebhookEventContextFactory.buildSessionEvent(
                WebhookScope.SESSION_CLOSED, "CLOSED", "sess-4", "张三\r\n\t\"hacker\"", Map.of());

        String body = sender.buildRequestBody(config, ctx);

        // 关键断言：控制字符/引号被转义后整体仍是合法 JSON，不会导致下游平台解析失败
        assertThatCode(() -> new ObjectMapper().readTree(body)).doesNotThrowAnyException();
        assertThat(body).contains("sess-4");
    }
}
