package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.service.IntentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HybridIntentService")
class HybridIntentServiceTest {

    @Mock private KeywordRegexIntentMatcher ruleMatcher;
    @Mock private LangChain4jIntentService llmClassifier;
    @InjectMocks private HybridIntentService service;

    @Test
    @DisplayName("Tier1 命中：返回规则结果，不调用 LLM")
    void classify_tier1Hit_llmNotCalled() {
        when(ruleMatcher.match("转人工"))
                .thenReturn(Optional.of(new IntentResult(IntentType.TRANSFER_REQUEST, "TRANSFER_REQUEST", 1.0)));

        IntentResult result = service.classify("转人工");

        assertThat(result.intent()).isEqualTo(IntentType.TRANSFER_REQUEST);
        assertThat(result.confidence()).isEqualTo(1.0);
        verify(llmClassifier, never()).classify(anyString());
    }

    @Test
    @DisplayName("Tier1 未命中：调用 LLM 分类器")
    void classify_tier1Miss_llmCalled() {
        when(ruleMatcher.match(anyString())).thenReturn(Optional.empty());
        when(llmClassifier.classify("退款政策是什么"))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, "FAQ_QUERY", 0.9));

        IntentResult result = service.classify("退款政策是什么");

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
        verify(llmClassifier).classify("退款政策是什么");
    }

    @Test
    @DisplayName("规则层抛异常：不传播，降级走 LLM")
    void classify_tier1Throws_fallsBackToLlm() {
        when(ruleMatcher.match(anyString())).thenThrow(new RuntimeException("规则层内部错误"));
        when(llmClassifier.classify(anyString()))
                .thenReturn(new IntentResult(IntentType.FAQ_QUERY, "FAQ_QUERY", 0.8));

        IntentResult result = service.classify("任意消息");

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
        verify(llmClassifier).classify(anyString());
    }
}
