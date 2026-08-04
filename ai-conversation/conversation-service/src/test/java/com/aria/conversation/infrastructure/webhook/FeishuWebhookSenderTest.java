package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
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
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("sess-001");
        session.setVisitorName("张三");
        SlaPolicyEntity policy = new SlaPolicyEntity();
        policy.setName("VIP-SLA");
        SlaBreachEntity breach = SlaBreachEntity.builder()
                .sessionId("sess-001").breachType(BreachType.WAIT).stage(BreachStage.BREACH)
                .targetSec(120).actualSec(185).build();
        WebhookEventContext ctx = WebhookEventContextFactory.buildSlaBreach(
                session, policy, List.of(breach));

        String body = sender.buildRequestBody(config, ctx);

        assertThat(body).contains("sess-001");
        assertThat(body).contains("排队等待超时");
        assertThat(body).contains("SLA 违规告警");
        assertThat(body).contains("\"template\": \"red\"");
    }

    @Test
    @DisplayName("buildRequestBody 默认模板输出为合法 JSON（含换行转义）")
    void buildRequestBody_defaultTemplate_parsesAsValidJson() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .url("https://example.com").build();
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("sess-001");
        session.setVisitorName("张三");
        SlaPolicyEntity policy = new SlaPolicyEntity();
        policy.setName("VIP-SLA");
        SlaBreachEntity breach = SlaBreachEntity.builder()
                .sessionId("sess-001").breachType(BreachType.WAIT).stage(BreachStage.BREACH)
                .targetSec(120).actualSec(185).build();
        WebhookEventContext ctx = WebhookEventContextFactory.buildSlaBreach(
                session, policy, List.of(breach));

        String body = sender.buildRequestBody(config, ctx);

        assertThatCode(() -> new ObjectMapper().readTree(body)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("自定义 Markdown 模板包装为飞书 text 消息 JSON")
    void buildRequestBody_customMarkdownTemplate_wrapsAsTextJson() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .url("https://example.com")
                .messageTemplate("### SLA 告警\n会话：${sessionId}")
                .build();
        WebhookEventContext ctx = WebhookEventContextFactory.buildSessionEvent(
                WebhookScope.SLA_BREACH, "WAIT", "sess-9", "访客A", Map.of());

        String body = sender.buildRequestBody(config, ctx);

        // 应包装为合法 JSON
        assertThatCode(() -> new ObjectMapper().readTree(body)).doesNotThrowAnyException();
        assertThat(body).contains("\"msg_type\": \"interactive\"");
        assertThat(body).contains("\"tag\": \"markdown\"");
        assertThat(body).contains("sess-9");
        assertThat(body).contains("### SLA 告警");
    }

    @Test
    @DisplayName("自定义 JSON 模板原样发送（向后兼容）")
    void buildRequestBody_customJsonTemplate_sentVerbatim() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .url("https://example.com")
                .messageTemplate("{\"msg_type\":\"text\",\"content\":{\"text\":\"自定义 ${sessionId}\"}}")
                .build();
        WebhookEventContext ctx = WebhookEventContextFactory.buildSessionEvent(
                WebhookScope.SLA_BREACH, "WAIT", "sess-9", null, Map.of());

        String body = sender.buildRequestBody(config, ctx);

        // 原样发送，不二次包装
        assertThat(body).startsWith("{");
        assertThat(body).contains("\"msg_type\":\"text\"");
        assertThat(body).contains("sess-9");
        assertThat(body).doesNotContain("msg_type\":\"text\",\"content\":{\"text\":\"自定义 sess-9\"}}\""); // 不嵌套
    }
}
