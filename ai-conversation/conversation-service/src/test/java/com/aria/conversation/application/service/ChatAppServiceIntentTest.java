package com.aria.conversation.application.service;

import com.aria.conversation.application.service.payload.ErrorPayload;
import com.aria.conversation.application.service.payload.TokenPayload;
import com.aria.conversation.application.service.payload.TransferPayload;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.service.DomainRoutingService;
import com.aria.conversation.domain.service.IntentService;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
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

/**
 * ChatAppService 意图路由单元测试。
 * 使用 service.stream(sessionId, message, null) 触发 FAQ 路径，
 * 验证返回的 {@link ChatEvent} 语义事件类型和内容。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatAppService 意图路由")
class ChatAppServiceIntentTest {

    @Mock private DynamicModelFactory aiClient;
    @Mock private ConversationHistoryRepository historyRepository;
    @Mock private KnowledgeClient knowledgeClient;
    @Mock private IntentService intentClassifier;
    @Mock private SessionQueueService sessionQueueService;
    @Mock private SessionDomainRepository sessionDomainRepo;
    @Mock private SessionDomainSwitchRepository domainSwitchRepo;
    @Mock private DomainRoutingService domainRoutingService;
    @Mock private DomainAgentService domainAgentService;

    private ChatAppService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ChatAppService(aiClient, historyRepository, knowledgeClient,
                intentClassifier, sessionQueueService, objectMapper,
                sessionDomainRepo, domainSwitchRepo, domainRoutingService,
                domainAgentService);
        // 大多数路径不需要 RAG 命中，默认返回空列表
        lenient().when(knowledgeClient.search(anyString())).thenReturn(List.of());
        // 转人工/拒答路径不走 findAll，允许该 stub 未被使用
        lenient().when(historyRepository.findAll(anyString())).thenReturn(List.of());
    }

    // -------------------------------------------------------
    // 正常 AI 回复路径
    // -------------------------------------------------------

    @Test
    @DisplayName("FAQ_QUERY: 返回 JSON 信封的 token 事件流，调用 LLM")
    void faqQuery_returnsDataEvents() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, 0.9));
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("这是", "回答"));

        Flux<ChatEvent> result = service.stream("s1", "退款政策是什么？", null);

        StepVerifier.create(result)
                .assertNext(e -> {
                    // token 事件：eventType == null，data 为 {"content":"..."} JSON 信封
                    assertThat(e.eventType()).isNull();
                    assertThat(readTokenContent(e)).isEqualTo("这是");
                })
                .assertNext(e -> assertThat(readTokenContent(e)).isEqualTo("回答"))
                .verifyComplete();
        verify(aiClient).streamChat(anyList(), anyString());
        verify(sessionQueueService, never()).enqueue(any(), any(), any(), any());
    }

    /** 从 token 事件解出 content 字段，隔离测试与 wire format 的耦合。 */
    private String readTokenContent(ChatEvent event) {
        try {
            return objectMapper.readValue(event.data(), TokenPayload.class).content();
        } catch (Exception e) {
            throw new AssertionError("token payload 解析失败: " + event.data(), e);
        }
    }

    /** 从 error 事件解出 message 字段。 */
    private String readErrorMessage(ChatEvent event) {
        try {
            return objectMapper.readValue(event.data(), ErrorPayload.class).message();
        } catch (Exception e) {
            throw new AssertionError("error payload 解析失败: " + event.data(), e);
        }
    }

    // -------------------------------------------------------
    // 转人工路径：验证 transfer 语义事件
    // -------------------------------------------------------

    @Test
    @DisplayName("TRANSFER_REQUEST: 发出 transfer 语义事件，不调 LLM")
    void transferRequest_emitsTransferEvent() throws Exception {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.TRANSFER_REQUEST, 0.95));

        Flux<ChatEvent> result = service.stream("s2", "我要找真人客服", null);

        StepVerifier.create(result)
                .assertNext(e -> {
                    assertThat(e.eventType()).isEqualTo(ChatEvent.EventType.TRANSFER);
                    // 反序列化 payload 验证 message 字段
                    TransferPayload payload;
                    try {
                        payload = objectMapper.readValue(e.data(), TransferPayload.class);
                    } catch (Exception ex) {
                        throw new AssertionError("transfer payload 解析失败", ex);
                    }
                    assertThat(payload.message()).contains("人工客服");
                    assertThat(payload.intentCode()).isEqualTo("faq_transfer");
                })
                .verifyComplete();
        verify(sessionQueueService).enqueue(eq("s2"), anyString(), anyString(), anyString());
        verify(aiClient, never()).streamChat(anyList(), anyString());
    }

    @Test
    @DisplayName("COMPLAINT: transfer 事件的 message 包含道歉语")
    void complaint_transferEventContainsApology() throws Exception {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.COMPLAINT, 0.93));

        Flux<ChatEvent> result = service.stream("s3", "你们服务太差了，我要投诉", null);

        StepVerifier.create(result)
                .assertNext(e -> {
                    assertThat(e.eventType()).isEqualTo(ChatEvent.EventType.TRANSFER);
                    try {
                        TransferPayload payload = objectMapper.readValue(e.data(), TransferPayload.class);
                        assertThat(payload.message()).contains("抱歉").contains("人工客服");
                    } catch (Exception ex) {
                        throw new AssertionError("transfer payload 解析失败", ex);
                    }
                })
                .verifyComplete();
        verify(sessionQueueService).enqueue(eq("s3"), anyString(), anyString(), anyString());
        verify(aiClient, never()).streamChat(anyList(), anyString());
    }

    // -------------------------------------------------------
    // 拒答路径
    // -------------------------------------------------------

    @Test
    @DisplayName("OUT_OF_SCOPE: 返回拒答 token 事件（JSON 信封），不调 LLM")
    void outOfScope_returnsDataWithTemplate() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.OUT_OF_SCOPE, 0.88));

        Flux<ChatEvent> result = service.stream("s4", "帮我解一道微积分题", null);

        StepVerifier.create(result)
                .assertNext(e -> {
                    assertThat(e.eventType()).isNull();
                    assertThat(readTokenContent(e)).contains("只能回答业务相关");
                })
                .verifyComplete();
        verify(aiClient, never()).streamChat(anyList(), anyString());
    }

    // -------------------------------------------------------
    // 闲聊路径
    // -------------------------------------------------------

    @Test
    @DisplayName("CHITCHAT: 跳过 RAG，调用 LLM，systemPrompt 不含参考资料")
    void chitchat_skipsRag() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.CHITCHAT, 0.9));
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("你好！"));
        // 模拟 knowledgeClient 返回命中，但 CHITCHAT 路径应跳过 RAG
        KnowledgeSearchResult.Hit hit = mock(KnowledgeSearchResult.Hit.class);
        when(knowledgeClient.search(anyString())).thenReturn(List.of(hit));

        Flux<ChatEvent> result = service.stream("s5", "你好", null);

        StepVerifier.create(result)
                .assertNext(e -> assertThat(readTokenContent(e)).isEqualTo("你好！"))
                .verifyComplete();
        // skipRag=true 时 systemPrompt 不拼入参考资料
        verify(aiClient).streamChat(anyList(), argThat(prompt -> !prompt.contains("【参考资料】")));
    }

    // -------------------------------------------------------
    // 降级路径
    // -------------------------------------------------------

    @Test
    @DisplayName("UNKNOWN: 降级走正常 FAQ 流程，调用 LLM")
    void unknown_fallsBackToFaqFlow() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(IntentResult.UNKNOWN);
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("正常回答"));

        Flux<ChatEvent> result = service.stream("s6", "随便问个问题", null);

        StepVerifier.create(result)
                .assertNext(e -> assertThat(readTokenContent(e)).isEqualTo("正常回答"))
                .verifyComplete();
        verify(aiClient).streamChat(anyList(), anyString());
    }

    // -------------------------------------------------------
    // Token wire format 契约（JSON 信封）
    // -------------------------------------------------------

    @Test
    @DisplayName("token 事件的 data 是合法 JSON，且 content 精确保留前导空格与换行")
    void tokenEvent_preservesWhitespaceViaJsonEnvelope() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, 0.9));
        // 模拟 LLM 分词器输出：token 天然带前导空格 + 换行
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.just("### ", " 🔴 ", "实时天气", "\n\n"));

        Flux<ChatEvent> result = service.stream("s-tok", "查天气", null);

        StepVerifier.create(result)
                .assertNext(e -> assertThat(readTokenContent(e)).isEqualTo("### "))
                .assertNext(e -> assertThat(readTokenContent(e)).isEqualTo(" 🔴 "))
                .assertNext(e -> assertThat(readTokenContent(e)).isEqualTo("实时天气"))
                .assertNext(e -> assertThat(readTokenContent(e)).isEqualTo("\n\n"))
                .verifyComplete();
    }

    // -------------------------------------------------------
    // 错误路径：JSON 信封
    // -------------------------------------------------------

    @Test
    @DisplayName("LLM 异常：走 event:error 事件（JSON 信封），data 为 {\"message\":\"...\"}")
    void llmError_emitsErrorEventWithJsonEnvelope() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, 0.9));
        when(aiClient.streamChat(anyList(), anyString()))
                .thenReturn(Flux.error(new RuntimeException("上游超时")));

        Flux<ChatEvent> result = service.stream("s-err", "问点啥", null);

        StepVerifier.create(result)
                .assertNext(e -> {
                    assertThat(e.eventType()).isEqualTo(ChatEvent.EventType.ERROR);
                    assertThat(readErrorMessage(e)).contains("AI 服务暂时不可用");
                })
                .verifyComplete();
    }

    // -------------------------------------------------------
    // 异常容错
    // -------------------------------------------------------

    @Test
    @DisplayName("enqueue 抛异常时，转人工失败不影响 transfer 事件推送")
    void transferRequest_enqueueFails_stillEmitsTransferEvent() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.TRANSFER_REQUEST, 0.9));
        doThrow(new RuntimeException("Redis 不可用"))
                .when(sessionQueueService).enqueue(any(), any(), any(), any());

        Flux<ChatEvent> result = service.stream("s7", "转人工", null);

        StepVerifier.create(result)
                .assertNext(e -> assertThat(e.eventType()).isEqualTo(ChatEvent.EventType.TRANSFER))
                .verifyComplete();
    }

    // -------------------------------------------------------
    // 向后兼容：@Deprecated streamChat() 降级契约
    // -------------------------------------------------------

    @Test
    @DisplayName("[deprecated] streamChat: transfer 场景降级返回提示文字，不返回空 Flux")
    @SuppressWarnings("deprecation")
    void deprecatedStreamChat_transferDegradesToText() {
        when(intentClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.TRANSFER_REQUEST, 0.9));

        Flux<String> result = service.streamChat("s8", "转人工");

        StepVerifier.create(result)
                .assertNext(msg -> assertThat(msg).contains("人工客服"))
                .verifyComplete();
    }
}
