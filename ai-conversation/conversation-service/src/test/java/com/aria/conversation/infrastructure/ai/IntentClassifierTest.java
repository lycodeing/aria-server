package com.aria.conversation.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
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
@DisplayName("IntentClassifier 意图分类器")
class IntentClassifierTest {

    @Mock
    private DynamicAiClient aiClient;

    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new IntentClassifier(aiClient, new ObjectMapper());
    }

    // ---- parseResponse 单元测试（不调 LLM）----

    @Test
    @DisplayName("parseResponse: 标准 JSON 正确解析")
    void parseResponse_standard() {
        IntentResult result = classifier.parseResponse("{\"intent\":\"FAQ_QUERY\",\"confidence\":0.9}");
        assertEquals(IntentType.FAQ_QUERY, result.intent());
        assertEquals(0.9, result.confidence(), 0.001);
    }

    @Test
    @DisplayName("parseResponse: markdown 代码块自动提取 JSON")
    void parseResponse_markdown() {
        String response = "```json\n{\"intent\":\"TRANSFER_REQUEST\",\"confidence\":0.95}\n```";
        IntentResult result = classifier.parseResponse(response);
        assertEquals(IntentType.TRANSFER_REQUEST, result.intent());
    }

    @Test
    @DisplayName("parseResponse: 未知意图值降级为 UNKNOWN")
    void parseResponse_unknownIntent() {
        IntentResult result = classifier.parseResponse("{\"intent\":\"BANANA\",\"confidence\":0.8}");
        assertEquals(IntentType.UNKNOWN, result.intent());
    }

    @Test
    @DisplayName("parseResponse: confidence 缺失时默认 1.0")
    void parseResponse_missingConfidence() {
        IntentResult result = classifier.parseResponse("{\"intent\":\"CHITCHAT\"}");
        assertEquals(IntentType.CHITCHAT, result.intent());
        assertEquals(1.0, result.confidence(), 0.001);
    }

    @Test
    @DisplayName("parseResponse: 空字符串返回 UNKNOWN")
    void parseResponse_empty() {
        assertEquals(IntentType.UNKNOWN, classifier.parseResponse("").intent());
        assertEquals(IntentType.UNKNOWN, classifier.parseResponse(null).intent());
    }

    @Test
    @DisplayName("parseResponse: 非法 JSON 返回 UNKNOWN")
    void parseResponse_invalidJson() {
        assertEquals(IntentType.UNKNOWN, classifier.parseResponse("not json at all").intent());
    }

    // ---- classify 集成测试（Mock DynamicAiClient）----

    @Test
    @DisplayName("classify: LLM 正常返回时解析意图")
    void classify_normal() {
        when(aiClient.chat(anyList(), anyString()))
                .thenReturn("{\"intent\":\"COMPLAINT\",\"confidence\":0.92}");

        IntentResult result = classifier.classify("我要投诉你们的服务太差了");

        assertEquals(IntentType.COMPLAINT, result.intent());
        assertEquals(0.92, result.confidence(), 0.001);
        verify(aiClient).chat(anyList(), anyString());
    }

    @Test
    @DisplayName("classify: LLM 抛出异常时降级为 UNKNOWN，不抛出")
    void classify_aiException_fallsBackToUnknown() {
        when(aiClient.chat(anyList(), anyString())).thenThrow(new RuntimeException("AI 超时"));

        IntentResult result = classifier.classify("测试消息");

        assertEquals(IntentType.UNKNOWN, result.intent());
        // 不应抛出异常
    }

    // ---- IntentResult 辅助方法测试 ----

    @Test
    @DisplayName("requiresTransfer: TRANSFER_REQUEST 和 COMPLAINT 为 true")
    void requiresTransfer() {
        assertTrue(new IntentResult(IntentType.TRANSFER_REQUEST, 0.9).requiresTransfer());
        assertTrue(new IntentResult(IntentType.COMPLAINT, 0.9).requiresTransfer());
        assertFalse(new IntentResult(IntentType.FAQ_QUERY, 0.9).requiresTransfer());
        assertFalse(new IntentResult(IntentType.CHITCHAT, 0.9).requiresTransfer());
    }

    @Test
    @DisplayName("skipRag: CHITCHAT 和 OUT_OF_SCOPE 为 true")
    void skipRag() {
        assertTrue(new IntentResult(IntentType.CHITCHAT, 0.9).skipRag());
        assertTrue(new IntentResult(IntentType.OUT_OF_SCOPE, 0.9).skipRag());
        assertFalse(new IntentResult(IntentType.FAQ_QUERY, 0.9).skipRag());
        assertFalse(new IntentResult(IntentType.TRANSFER_REQUEST, 0.9).skipRag());
    }
}
