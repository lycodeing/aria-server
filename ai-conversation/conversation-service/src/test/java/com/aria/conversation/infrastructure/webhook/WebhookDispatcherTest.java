package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import com.aria.conversation.infrastructure.persistence.mapper.WebhookConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.clearInvocations;

@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    @Mock WebhookConfigMapper webhookConfigMapper;
    @Mock SlaBreachMapper     slaBreachMapper;
    @Mock WebhookSender       feishuSender;

    WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(feishuSender.supportedType()).thenReturn("FEISHU");
        dispatcher = new WebhookDispatcher(
                List.of(feishuSender), webhookConfigMapper, slaBreachMapper);
        // supportedType() was called during construction to build the router map;
        // clear that recorded interaction so verifyNoInteractions() in tests is accurate.
        clearInvocations(feishuSender);
    }

    @Test
    @DisplayName("启用的 Webhook 配置调用对应 sender.send()")
    void dispatch_callsSenderForEnabledConfig() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .id(1L).type("FEISHU").url("https://example.com").isEnabled(1).build();
        when(webhookConfigMapper.selectEnabledByIds(List.of(1L))).thenReturn(List.of(config));

        SlaBreachEntity breach = SlaBreachEntity.builder()
                .id(10L).sessionId("s1").breachType("WAIT").stage("BREACH")
                .targetSec(120).actualSec(185).build();
        SlaBreachContext ctx = new SlaBreachContext("s1", "张三", "默认SLA", List.of(breach));

        dispatcher.dispatch(List.of(1L), ctx, List.of(10L));

        verify(feishuSender).send(eq(config), eq(ctx));
        verify(slaBreachMapper).updateWebhookNotifiedAt(eq(List.of(10L)), any());
    }

    @Test
    @DisplayName("sender.send() 抛出异常时不影响其他 Webhook 执行")
    void dispatch_senderException_doesNotAbortOthers() {
        WebhookConfigEntity config = WebhookConfigEntity.builder()
                .id(1L).type("FEISHU").url("https://example.com").isEnabled(1).build();
        when(webhookConfigMapper.selectEnabledByIds(any())).thenReturn(List.of(config));
        doThrow(new RuntimeException("timeout")).when(feishuSender).send(any(), any());

        SlaBreachContext ctx = new SlaBreachContext("s1", "张三", "默认SLA",
                List.of(SlaBreachEntity.builder().breachType("WAIT").stage("BREACH")
                        .targetSec(120).actualSec(185).build()));

        // 不应抛出异常
        dispatcher.dispatch(List.of(1L), ctx, List.of(10L));

        // 发送失败时不标记 webhook_notified_at
        verify(slaBreachMapper, never()).updateWebhookNotifiedAt(any(), any());
    }

    @Test
    @DisplayName("webhookIds 为空时不调用任何 sender")
    void dispatch_emptyIds_doesNothing() {
        dispatcher.dispatch(List.of(), new SlaBreachContext("s1", "张三", "SLA", List.of()), List.of());
        verifyNoInteractions(webhookConfigMapper, feishuSender);
    }
}
