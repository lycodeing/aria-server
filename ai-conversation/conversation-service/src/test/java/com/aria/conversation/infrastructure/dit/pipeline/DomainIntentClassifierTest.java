package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicAiClient;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomainIntentClassifier 领域感知意图分类器")
class DomainIntentClassifierTest {

    @Mock private DynamicAiClient aiClient;
    private DomainIntentClassifier classifier;

    private static IntentConfig intent(String code, String desc) {
        return new IntentConfig(code, code + "_name", desc, "[]",
                false, false, null, List.of(), List.of());
    }

    @BeforeEach
    void setUp() {
        classifier = new DomainIntentClassifier(aiClient, new ObjectMapper());
    }

    // ---- parseResponse ----

    @Test
    @DisplayName("parseResponse: 标准 JSON，intentCode 在意图列表中")
    void parseResponse_valid() {
        List<IntentConfig> intents = List.of(intent("query_order", "查询订单"));
        DomainIntentClassifier.DomainIntentResult r =
                classifier.parseResponse("{\"intentCode\":\"query_order\",\"confidence\":0.92}", intents);
        assertEquals("query_order", r.intentCode());
        assertEquals(0.92, r.confidence(), 0.001);
    }

    @Test
    @DisplayName("parseResponse: intentCode 不在意图列表中，降级 UNKNOWN")
    void parseResponse_unknownCode() {
        List<IntentConfig> intents = List.of(intent("query_order", "查询订单"));
        DomainIntentClassifier.DomainIntentResult r =
                classifier.parseResponse("{\"intentCode\":\"banana\",\"confidence\":0.8}", intents);
        assertTrue(r.isUnknown());
    }

    @Test
    @DisplayName("parseResponse: UNKNOWN 是保留值，直接返回")
    void parseResponse_explicitUnknown() {
        List<IntentConfig> intents = List.of(intent("query_order", "查询订单"));
        DomainIntentClassifier.DomainIntentResult r =
                classifier.parseResponse("{\"intentCode\":\"UNKNOWN\",\"confidence\":0.5}", intents);
        assertTrue(r.isUnknown());
    }

    @Test
    @DisplayName("parseResponse: 空响应返回 UNKNOWN")
    void parseResponse_empty() {
        assertTrue(classifier.parseResponse(null, List.of()).isUnknown());
        assertTrue(classifier.parseResponse("", List.of()).isUnknown());
    }

    @Test
    @DisplayName("parseResponse: markdown 代码块自动提取 JSON")
    void parseResponse_markdown() {
        List<IntentConfig> intents = List.of(intent("apply_refund", "申请退款"));
        DomainIntentClassifier.DomainIntentResult r = classifier.parseResponse(
                "```json\n{\"intentCode\":\"apply_refund\",\"confidence\":0.95}\n```", intents);
        assertEquals("apply_refund", r.intentCode());
    }

    // ---- classify ----

    @Test
    @DisplayName("classify: 空意图列表返回 UNKNOWN，不调 LLM")
    void classify_emptyIntents_noLlmCall() {
        DomainIntentClassifier.DomainIntentResult r = classifier.classify("查订单", List.of());
        assertTrue(r.isUnknown());
        verify(aiClient, never()).chat(anyList(), anyString());
    }

    @Test
    @DisplayName("classify: LLM 正常返回时解析意图")
    void classify_normal() {
        when(aiClient.chat(anyList(), anyString()))
                .thenReturn("{\"intentCode\":\"query_order\",\"confidence\":0.93}");
        List<IntentConfig> intents = List.of(intent("query_order", "查询订单"));

        DomainIntentClassifier.DomainIntentResult r = classifier.classify("帮我查订单", intents);

        assertEquals("query_order", r.intentCode());
        verify(aiClient).chat(anyList(), anyString());
    }

    @Test
    @DisplayName("classify: LLM 抛异常降级 UNKNOWN，不抛出")
    void classify_aiException_fallsBackToUnknown() {
        when(aiClient.chat(anyList(), anyString())).thenThrow(new RuntimeException("超时"));
        List<IntentConfig> intents = List.of(intent("query_order", "查询订单"));

        DomainIntentClassifier.DomainIntentResult r = classifier.classify("查订单", intents);
        assertTrue(r.isUnknown());
    }
}
