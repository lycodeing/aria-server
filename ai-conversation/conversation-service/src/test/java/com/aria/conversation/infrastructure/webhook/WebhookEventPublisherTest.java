package com.aria.conversation.infrastructure.webhook;

import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
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

@ExtendWith(MockitoExtension.class)
class WebhookEventPublisherTest {

    @Mock WebhookConfigMapper webhookConfigMapper;
    @Mock WebhookDispatcher   webhookDispatcher;

    WebhookEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new WebhookEventPublisher(webhookConfigMapper, webhookDispatcher);
    }

    @Test
    @DisplayName("无匹配 webhook 时零分发且不抛异常")
    void publish_noMatch_skips() {
        when(webhookConfigMapper.selectEnabledByScope("SLA_BREACH"))
                .thenReturn(List.of());

        publisher.publish(WebhookScope.SLA_BREACH,
                WebhookEventContext.builder().eventType("WAIT").build());

        verify(webhookDispatcher, never()).dispatch(any(), any());
    }

    @Test
    @DisplayName("命中多个 webhook 时逐个分发，ctx.scope 以入参为准")
    void publish_matched_dispatchesEach() {
        WebhookConfigEntity a = WebhookConfigEntity.builder().id(1L).type("FEISHU").build();
        WebhookConfigEntity b = WebhookConfigEntity.builder().id(2L).type("CUSTOM").build();
        when(webhookConfigMapper.selectEnabledByScope("SESSION_CLOSED"))
                .thenReturn(List.of(a, b));

        WebhookEventContext ctx = WebhookEventContext.builder().eventType("CLOSED").build();
        publisher.publish(WebhookScope.SESSION_CLOSED, ctx);

        verify(webhookDispatcher).dispatch(eq(a), any(WebhookEventContext.class));
        verify(webhookDispatcher).dispatch(eq(b), any(WebhookEventContext.class));
        // 入参 scope 覆盖 ctx 未设置值
        verify(webhookDispatcher, times(2))
                .dispatch(any(), argThat(c -> c.getScope() == WebhookScope.SESSION_CLOSED));
    }

    @Test
    @DisplayName("DB 查询异常时故障隔离：不抛异常、不分发（关键）")
    void publish_mapperThrows_isSwallowed() {
        when(webhookConfigMapper.selectEnabledByScope("SLA_BREACH"))
                .thenThrow(new RuntimeException("db down"));

        // 不抛异常
        publisher.publish(WebhookScope.SLA_BREACH,
                WebhookEventContext.builder().sessionId("sess-1").build());

        verify(webhookDispatcher, never()).dispatch(any(), any());
    }

    @Test
    @DisplayName("ctx 已显式设置 scope 时不被覆盖")
    void publish_explicitScope_keepsValue() {
        WebhookConfigEntity a = WebhookConfigEntity.builder().id(1L).type("FEISHU").build();
        when(webhookConfigMapper.selectEnabledByScope("SLA_BREACH"))
                .thenReturn(List.of(a));

        // 调用方显式设置了一个"错误"scope，publisher 不得覆盖
        WebhookEventContext ctx = WebhookEventContext.builder()
                .scope(WebhookScope.CSAT_RATED)
                .build();
        publisher.publish(WebhookScope.SLA_BREACH, ctx);

        verify(webhookDispatcher).dispatch(eq(a),
                argThat(c -> c.getScope() == WebhookScope.CSAT_RATED));
    }
}
