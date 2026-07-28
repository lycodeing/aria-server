package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.*;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.aria.conversation.infrastructure.example.IntentExampleVectorRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MultiHybridIntentService 三级级联")
class MultiHybridIntentServiceTest {

    @Mock private KeywordRegexIntentMatcher ruleMatcher;
    @Mock private EmbeddingPrototypeIntentMatcher embeddingMatcher;
    @Mock private MultiIntentClassifier llmClassifier;
    @Mock private RoutingConfigProvider routingConfigProvider;
    @Mock private IntentExampleVectorRepository exampleVectorRepo;
    @Mock private EmbeddingService embeddingService;

    private MultiHybridIntentService service;

    @BeforeEach
    void setUp() {
        RoutingConfig config = new RoutingConfig();
        config.getIntent().setMultiIntentEnabled(true);
        config.getIntent().setEmbeddingEnabled(true);
        config.getIntent().setEmbeddingHighConfidence(0.85);
        when(routingConfigProvider.getConfig()).thenReturn(config);

        service = new MultiHybridIntentService(
                ruleMatcher, embeddingMatcher, llmClassifier,
                routingConfigProvider, new SimpleMeterRegistry(),
                exampleVectorRepo, embeddingService);
    }

    @Test
    @DisplayName("Tier1 命中 COMPLAINT，hasTransfer=true，跳过 Tier3")
    void classifyMulti_tier1Complaint_skipsTier3() {
        when(ruleMatcher.matchAll(any())).thenReturn(List.of(
                new IntentResult(IntentType.COMPLAINT, "complaint", 1.0)));
        when(embeddingMatcher.match(any())).thenReturn(List.of());

        var result = service.classifyMulti("我要投诉");

        assertThat(result.requiresTransfer()).isTrue();
        assertThat(result.sourceTier()).isEqualTo("RULE");
        verify(llmClassifier, never()).classifyMulti(any());
    }

    @Test
    @DisplayName("Tier2 高置信度命中（>=0.85），跳过 Tier3")
    void classifyMulti_tier2HighConf_skipsTier3() {
        when(ruleMatcher.matchAll(any())).thenReturn(List.of());
        when(embeddingMatcher.match(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "claim_apply", 0.91)));

        var result = service.classifyMulti("申请理赔");

        assertThat(result.intents()).hasSize(1);
        assertThat(result.sourceTier()).isEqualTo("EMBEDDING");
        verify(llmClassifier, never()).classifyMulti(any());
    }

    @Test
    @DisplayName("Tier1+Tier2 均无命中，触发 Tier3 LLM")
    void classifyMulti_noTier1Tier2_fallsBackToLlm() {
        when(ruleMatcher.matchAll(any())).thenReturn(List.of());
        when(embeddingMatcher.match(any())).thenReturn(List.of());
        when(llmClassifier.classifyMulti(any())).thenReturn(List.of(
                new IntentResult(IntentType.CHITCHAT, "chitchat", 0.95)));

        var result = service.classifyMulti("哈哈哈");

        assertThat(result.sourceTier()).isEqualTo("LLM");
        verify(llmClassifier).classifyMulti(any());
    }

    @Test
    @DisplayName("Tier1+Tier2 命中不同意图，合并返回两个")
    void classifyMulti_tier1AndTier2_differentIntents_mergedBoth() {
        when(ruleMatcher.matchAll(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "query_logistics", 1.0)));
        when(embeddingMatcher.match(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "query_logistics", 0.88),  // 重复不覆盖
                new IntentResult(IntentType.FAQ_QUERY, "cancel_order", 0.81)));

        var result = service.classifyMulti("查物流同时取消订单");

        assertThat(result.intentCodes())
                .containsExactlyInAnyOrder("query_logistics", "cancel_order");
        // Tier1 的 query_logistics(1.0) 不被 Tier2(0.88) 覆盖
        assertThat(result.intents().stream()
                .filter(r -> r.intentCode().equals("query_logistics"))
                .findFirst().get().confidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("multiIntentEnabled=false，退化为单意图")
    void classifyMulti_disabled_degrades() {
        RoutingConfig config = new RoutingConfig();
        config.getIntent().setMultiIntentEnabled(false);
        when(routingConfigProvider.getConfig()).thenReturn(config);
        when(ruleMatcher.matchAll(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 1.0)));

        var result = service.classifyMulti("查订单");

        assertThat(result.intents()).hasSize(1);
    }

    @Test
    @DisplayName("Tier1 抛异常，降级走 Tier2")
    void classifyMulti_tier1Exception_degradesToTier2() {
        when(ruleMatcher.matchAll(any())).thenThrow(new RuntimeException("规则层异常"));
        when(embeddingMatcher.match(any())).thenReturn(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.90)));

        var result = service.classifyMulti("查询");

        assertThat(result.intents()).hasSize(1);
        assertThat(result.sourceTier()).isEqualTo("EMBEDDING");
    }
}
