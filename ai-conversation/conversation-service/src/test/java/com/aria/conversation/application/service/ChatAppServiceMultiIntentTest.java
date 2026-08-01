package com.aria.conversation.application.service;

import com.aria.conversation.domain.model.*;
import com.aria.conversation.domain.service.MultiIntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAppService 多意图路由")
class ChatAppServiceMultiIntentTest {

    @Mock private SessionQueueService       sessionQueueService;
    @Mock private DomainSessionAppService   domainSessionService;
    @Mock private FaqChatAppService         faqChatService;
    @Mock private DomainAgentService        domainAgentService;
    @Mock private MultiIntentService        multiIntentService;

    private ChatAppService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private com.aria.conversation.application.service.cancellation.CancellationRegistry cancellationRegistry;
    @Mock private com.aria.common.web.redis.RedisCacheHelper cache;
    @Mock private com.aria.common.web.redis.RedisCounterHelper counter;

    @BeforeEach
    void setUp() {
        service = new ChatAppService(sessionQueueService, domainSessionService,
                faqChatService, domainAgentService, multiIntentService, objectMapper,
                cancellationRegistry, cache, counter);
    }

    private MultiIntentResult multiOf(IntentType type, String code) {
        return new MultiIntentResult(
                List.of(new IntentResult(type, code, 1.0)), "RULE", 1L);
    }

    @Test
    @DisplayName("多意图含COMPLAINT + FAQ_QUERY：union语义触发转人工")
    void stream_multiIntentWithComplaint_triggersTransfer() {
        when(sessionQueueService.isActive("s1")).thenReturn(false);
        when(domainSessionService.resolveActiveDomain("s1", "投诉加查物流", "ec"))
                .thenReturn("ec");
        var multi = new MultiIntentResult(List.of(
                new IntentResult(IntentType.COMPLAINT, "complaint", 1.0),
                new IntentResult(IntentType.FAQ_QUERY, "query_logistics", 0.88)
        ), "RULE", 30L);
        when(multiIntentService.classifyMulti("投诉加查物流", "ec")).thenReturn(multi);
        when(faqChatService.handleTransfer(eq("s1"), any()))
                .thenReturn(Flux.just(ChatEvent.transfer("{}")));

        StepVerifier.create(service.stream("s1", "投诉加查物流", "ec", null))
                .assertNext(e -> assertThat(e.eventType())
                        .isEqualTo(ChatEvent.EventType.TRANSFER))
                .verifyComplete();

        verify(domainAgentService, never()).streamChat(any(), any(), any(), any());
    }

    @Test
    @DisplayName("多意图无转人工：intentCodes 透传给 DomainAgent")
    void stream_multiIntentNoTransfer_intentCodesPropagated() {
        when(sessionQueueService.isActive("s2")).thenReturn(false);
        when(domainSessionService.resolveActiveDomain("s2", "查物流取消订单", "ec"))
                .thenReturn("ec");
        var multi = new MultiIntentResult(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "query_logistics", 1.0),
                new IntentResult(IntentType.FAQ_QUERY, "cancel_order", 0.81)
        ), "EMBEDDING", 30L);
        when(multiIntentService.classifyMulti("查物流取消订单", "ec")).thenReturn(multi);
        when(domainAgentService.streamChat(eq("s2"), eq("ec"), eq("查物流取消订单"), any()))
                .thenReturn(Flux.just(ChatEvent.token("处理中", objectMapper)));

        service.stream("s2", "查物流取消订单", "ec", null).blockLast();

        verify(domainAgentService).streamChat(eq("s2"), eq("ec"), eq("查物流取消订单"),
                argThat(codes -> codes.contains("query_logistics")
                        && codes.contains("cancel_order")));
    }

    @Test
    @DisplayName("单意图FAQ_QUERY：委托 DomainAgentService")
    void stream_singleFaqIntent_delegatesToDomainAgent() {
        when(sessionQueueService.isActive("s3")).thenReturn(false);
        when(domainSessionService.resolveActiveDomain("s3", "查订单", "ec"))
                .thenReturn("ec");
        when(multiIntentService.classifyMulti("查订单", "ec"))
                .thenReturn(multiOf(IntentType.FAQ_QUERY, "faq_query"));
        when(domainAgentService.streamChat(eq("s3"), eq("ec"), eq("查订单"), any()))
                .thenReturn(Flux.just(ChatEvent.token("好的", objectMapper)));

        service.stream("s3", "查订单", "ec", null).blockLast();

        verify(domainAgentService).streamChat(eq("s3"), eq("ec"), eq("查订单"), any());
        verify(faqChatService, never()).handleTransfer(any(), any());
    }
}
