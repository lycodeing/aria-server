package com.aria.conversation.application.service;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.service.IntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ChatAppService 三路分发单元测试。
 *
 * <p>验证路由分发逻辑：已接入人工、无 domainCode（FAQ）、有 domainCode + 转人工意图。
 * 详细的意图路由场景测试属于 FaqChatAppServiceTest。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAppService 三路分发")
class ChatAppServiceIntentTest {

    @Mock private SessionQueueService       sessionQueueService;
    @Mock private DomainSessionAppService   domainSessionService;
    @Mock private FaqChatAppService         faqChatService;
    @Mock private DomainAgentService        domainAgentService;
    @Mock private IntentService             intentClassifier;

    private ChatAppService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ChatAppService(sessionQueueService, domainSessionService,
                faqChatService, domainAgentService, intentClassifier, objectMapper);
    }

    @Test
    @DisplayName("已接入人工：委托 faqChatService.appendAndHint")
    void stream_agentActive_delegatesToAppendAndHint() {
        when(sessionQueueService.isActive("s1")).thenReturn(true);
        when(faqChatService.appendAndHint("s1", "消息")).thenReturn(
                Flux.just(ChatEvent.token("已发送", objectMapper)));

        StepVerifier.create(service.stream("s1", "消息", null))
                .assertNext(e -> assertThat(e.eventType()).isNull())
                .verifyComplete();
        verify(faqChatService).appendAndHint("s1", "消息");
    }

    @Test
    @DisplayName("无 domainCode：委托 faqChatService.stream")
    void stream_noDomainCode_delegatesToFaqStream() {
        when(sessionQueueService.isActive("s2")).thenReturn(false);
        when(faqChatService.stream("s2", "查订单")).thenReturn(Flux.empty());

        service.stream("s2", "查订单", null).blockLast();

        verify(faqChatService).stream("s2", "查订单");
        verify(domainAgentService, never()).streamChat(any(), any(), any());
    }

    @Test
    @DisplayName("有 domainCode + requiresTransfer：委托 faqChatService.handleTransfer，不走 DomainAgent")
    void stream_domainCode_transferIntent_delegatesToHandleTransfer() {
        when(sessionQueueService.isActive("s3")).thenReturn(false);
        when(domainSessionService.resolveActiveDomain("s3", "转人工", "ecommerce"))
                .thenReturn("ecommerce");
        when(intentClassifier.classify("转人工"))
                .thenReturn(new IntentResult(IntentType.TRANSFER_REQUEST, "transfer_request", 1.0));
        when(faqChatService.handleTransfer(eq("s3"), any()))
                .thenReturn(Flux.just(ChatEvent.transfer("{}")));

        StepVerifier.create(service.stream("s3", "转人工", "ecommerce"))
                .assertNext(e -> assertThat(e.eventType()).isEqualTo(ChatEvent.EventType.TRANSFER))
                .verifyComplete();
        verify(domainAgentService, never()).streamChat(any(), any(), any());
    }
}
