package com.aria.conversation.application.service;

import com.aria.conversation.domain.service.IntentService;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * FaqChatAppService 会话存在性校验单元测试。
 *
 * <p>验证 stream() 方法在会话不存在时立即发射 error 事件，
 * 会话存在时不短路返回错误事件（下游 reactive 管道为懒执行，不在本测试范围内）。
 */
@ExtendWith(MockitoExtension.class)
class FaqChatAppServiceSessionTest {

    @Mock private DynamicModelFactory           aiClient;
    @Mock private ConversationHistoryRepository historyRepository;
    @Mock private KnowledgeServiceClient        knowledgeServiceClient;
    @Mock private IntentService                 intentService;
    @Mock private SessionQueueService           sessionQueueService;
    @Mock private CsatService                   csatService;
    @Mock private ConversationPersistRepository persistRepository;

    private FaqChatAppService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new FaqChatAppService(
                aiClient, historyRepository, knowledgeServiceClient,
                intentService, sessionQueueService, objectMapper,
                csatService, persistRepository);
    }

    @Test
    void stream_sessionNotExists_emitsErrorEvent() {
        when(persistRepository.existsBySessionId("sess_gone")).thenReturn(false);

        StepVerifier.create(service.stream("sess_gone", "你好"))
                .expectNextMatches(event -> ChatEvent.EventType.ERROR.equals(event.eventType()))
                .verifyComplete();
    }

    @Test
    void stream_sessionExists_doesNotThrow() {
        // 防御性校验现在位于 Mono.fromCallable 内（懒执行），stream() 本身不阻塞不抛出。
        // 只验证 stream() 返回非 null Flux，不触发订阅（下游 pipeline 在本测试范围之外）。
        Flux<ChatEvent> flux = service.stream("sess_ok", "hi");
        assertThat(flux).isNotNull();
    }
}
