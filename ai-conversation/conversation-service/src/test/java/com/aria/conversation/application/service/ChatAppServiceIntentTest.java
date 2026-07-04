package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.ai.DynamicAiClient;
import com.aria.conversation.infrastructure.ai.IntentClassifier;
import com.aria.conversation.infrastructure.ai.IntentResult;
import com.aria.conversation.infrastructure.ai.IntentType;
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline;
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline.RouteResult;
import com.aria.conversation.infrastructure.dit.pipeline.ToolExecutor;
import com.aria.conversation.infrastructure.knowledge.KnowledgeClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAppService 意图路由")
class ChatAppServiceIntentTest {

    @Mock private DynamicAiClient aiClient;
    @Mock private ConversationHistoryRepository historyRepository;
    @Mock private KnowledgeClient knowledgeClient;
    @Mock private IntentClassifier intentClassifier;
    @Mock private SessionQueueService sessionQueueService;
    @Mock private DitPipeline ditPipeline;
    @Mock private ToolExecutor toolExecutor;

    private ChatAppService service;

    @BeforeEach
    void setUp() {
        service = new ChatAppService(aiClient, historyRepository, knowledgeClient,
                intentClassifier, sessionQueueService, ditPipeline, toolExecutor);
        // lenient: 转人工/拒答路径不走 findAll，允许该 stub 未被使用
        lenient().when(historyRepository.findAll(anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("FAQ_QUERY: 正常调用 LLM 流式回复")
    void faqQuery_callsAiStream() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, 0.9));
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("这是", "回答"));

        Flux<String> result = service.streamChat("s1", "退款政策是什么？", List.of());

        StepVerifier.create(result)
                .expectNext("这是", "回答")
                .verifyComplete();
        verify(aiClient).streamChat(anyList(), anyString());
        verify(sessionQueueService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    @DisplayName("TRANSFER_REQUEST: 自动入队转人工，返回提示文本，不调 LLM")
    void transferRequest_enqueuedAndNoAi() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.TRANSFER_REQUEST, 0.95));

        Flux<String> result = service.streamChat("s2", "我要找真人客服", List.of());

        StepVerifier.create(result)
                .expectNextMatches(msg -> msg.contains("人工客服"))
                .verifyComplete();
        verify(sessionQueueService).enqueue(eq("s2"), anyString(), anyString(), anyString());
        verify(aiClient, never()).streamChat(anyList(), anyString());
    }

    @Test
    @DisplayName("COMPLAINT: 自动入队转人工，回复包含道歉语")
    void complaint_enqueuedWithApology() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.COMPLAINT, 0.93));

        Flux<String> result = service.streamChat("s3", "你们服务太差了，我要投诉", List.of());

        StepVerifier.create(result)
                .expectNextMatches(msg -> msg.contains("抱歉") && msg.contains("人工客服"))
                .verifyComplete();
        verify(sessionQueueService).enqueue(eq("s3"), anyString(), anyString(), anyString());
        verify(aiClient, never()).streamChat(anyList(), anyString());
    }

    @Test
    @DisplayName("OUT_OF_SCOPE: 返回拒答模板，不调 LLM")
    void outOfScope_returnsTemplate() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.OUT_OF_SCOPE, 0.88));

        Flux<String> result = service.streamChat("s4", "帮我解一道微积分题", List.of());

        StepVerifier.create(result)
                .expectNextMatches(msg -> msg.contains("只能回答业务相关"))
                .verifyComplete();
        verify(aiClient, never()).streamChat(anyList(), anyString());
    }

    @Test
    @DisplayName("CHITCHAT: 跳过 RAG（hits 有值也忽略），直接调 LLM，systemPrompt 不含参考资料")
    void chitchat_skipsRag() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.CHITCHAT, 0.9));
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("你好！"));
        KnowledgeSearchResult.Hit hit = mock(KnowledgeSearchResult.Hit.class);

        Flux<String> result = service.streamChat("s5", "你好", List.of(hit));

        StepVerifier.create(result)
                .expectNext("你好！")
                .verifyComplete();
        verify(aiClient).streamChat(anyList(), argThat(prompt -> !prompt.contains("【参考资料】")));
    }

    @Test
    @DisplayName("UNKNOWN: 降级走正常 FAQ 流程，调用 LLM")
    void unknown_fallsBackToFaqFlow() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(IntentResult.UNKNOWN);
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("正常回答"));

        Flux<String> result = service.streamChat("s6", "随便问个问题", List.of());

        StepVerifier.create(result)
                .expectNext("正常回答")
                .verifyComplete();
        verify(aiClient).streamChat(anyList(), anyString());
    }

    @Test
    @DisplayName("enqueue 抛异常时，转人工失败不影响最终回复推送")
    void transferRequest_enqueueFails_stillReturnsReply() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.TRANSFER_REQUEST, 0.9));
        doThrow(new RuntimeException("Redis 不可用"))
                .when(sessionQueueService).enqueue(any(), any(), any(), any());

        Flux<String> result = service.streamChat("s7", "转人工", List.of());

        StepVerifier.create(result)
                .expectNextMatches(msg -> msg.contains("人工客服"))
                .verifyComplete();
    }
}
