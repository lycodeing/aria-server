package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatcherTest {

    @Mock WebhookSender feishuSender;

    WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(feishuSender.supportedType()).thenReturn("FEISHU");
        dispatcher = new WebhookDispatcher(List.of(feishuSender), 0L);
        clearInvocations(feishuSender);
    }

    @Test
    @DisplayName("推送成功后调用 onSuccess 回调（通用，不区分 scope）")
    void dispatch_success_invokesOnSuccess() {
        WebhookConfigEntity config = WebhookConfigEntity.builder().id(1L).type("FEISHU").build();
        AtomicInteger calls = new AtomicInteger();
        WebhookEventContext ctx = WebhookEventContext.builder()
                .scope(WebhookScope.SLA_BREACH)
                .eventType("WAIT")
                .sessionId("sess-1")
                .onSuccess(calls::incrementAndGet)
                .build();

        dispatcher.dispatch(config, ctx);

        verify(feishuSender).send(eq(config), any(WebhookEventContext.class));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("推送失败不调用回调且不抛异常")
    void dispatch_failure_skipsCallback() {
        WebhookConfigEntity config = WebhookConfigEntity.builder().id(2L).type("FEISHU").build();
        AtomicInteger calls = new AtomicInteger();
        doThrow(new RuntimeException("network down"))
                .when(feishuSender).send(eq(config), any(WebhookEventContext.class));
        WebhookEventContext ctx = WebhookEventContext.builder()
                .scope(WebhookScope.SESSION_CLOSED)
                .sessionId("sess-2")
                .onSuccess(calls::incrementAndGet)
                .build();

        dispatcher.dispatch(config, ctx); // 不抛异常

        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("未知类型 sender 跳过")
    void dispatch_unknownType_skips() {
        WebhookConfigEntity config = WebhookConfigEntity.builder().id(3L).type("WHAT").build();

        dispatcher.dispatch(config,
                WebhookEventContext.builder().scope(WebhookScope.SLA_BREACH).build());

        verify(feishuSender, never()).send(any(), any());
    }

    @Test
    @DisplayName("webhook 为 null 时安全返回")
    void dispatch_nullWebhook_safe() {
        dispatcher.dispatch(null, WebhookEventContext.builder().build());
        verify(feishuSender, never()).send(any(), any());
    }
}
