package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.aria.conversation.infrastructure.prototype.IntentPrototypeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingPrototypeIntentMatcher Tier2")
class EmbeddingPrototypeIntentMatcherTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private IntentPrototypeStore prototypeStore;
    @Mock private RoutingConfigProvider routingConfigProvider;

    private EmbeddingPrototypeIntentMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new EmbeddingPrototypeIntentMatcher(
                embeddingService, prototypeStore, routingConfigProvider);
    }

    private RoutingConfig configWith(double globalThreshold) {
        RoutingConfig config = new RoutingConfig();
        config.getIntent().setEmbeddingGlobalThreshold(globalThreshold);
        config.getIntent().setEmbeddingThresholds(Map.of());
        return config;
    }

    @Test
    @DisplayName("相似度超过全局阈值的意图被返回")
    void match_aboveThreshold_returnsIntent() {
        when(routingConfigProvider.getConfig()).thenReturn(configWith(0.75));
        // 查询向量和 FAQ_QUERY 原型向量相同（余弦相似度 ≈ 1.0）
        float[] vec = {1.0f, 0.0f};  // 已归一化
        when(embeddingService.encode(any())).thenReturn(vec);
        when(prototypeStore.getAllPrototypes()).thenReturn(Map.of("FAQ_QUERY", vec));

        List<IntentResult> results = matcher.match("查一下我的订单");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.FAQ_QUERY);
        assertThat(results.get(0).confidence()).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    @DisplayName("相似度低于阈值的意图不返回")
    void match_belowThreshold_returnsEmpty() {
        when(routingConfigProvider.getConfig()).thenReturn(configWith(0.9));
        float[] query = {1.0f, 0.0f};
        float[] proto = {0.0f, 1.0f};  // 正交，余弦相似度 = 0
        when(embeddingService.encode(any())).thenReturn(query);
        when(prototypeStore.getAllPrototypes()).thenReturn(Map.of("FAQ_QUERY", proto));

        assertThat(matcher.match("查一下我的订单")).isEmpty();
    }

    @Test
    @DisplayName("原型库为空时返回空列表")
    void match_emptyPrototypes_returnsEmpty() {
        when(prototypeStore.getAllPrototypes()).thenReturn(Map.of());
        // 原型库为空时直接返回，不调用 embeddingService.encode()

        assertThat(matcher.match("查询")).isEmpty();
    }

    @Test
    @DisplayName("空白消息返回空列表，不调用 EmbeddingService")
    void match_blankMessage_returnsEmpty() {
        assertThat(matcher.match("  ")).isEmpty();
    }

    @Test
    @DisplayName("意图独立阈值覆盖全局阈值（阈值更高不命中）")
    void match_intentSpecificThreshold_overridesGlobal() {
        RoutingConfig config = new RoutingConfig();
        config.getIntent().setEmbeddingGlobalThreshold(0.5);
        config.getIntent().setEmbeddingThresholds(Map.of("claim_apply", 0.95));
        when(routingConfigProvider.getConfig()).thenReturn(config);

        // claim_apply 原型与 query 余弦相似度约 0.8（未达独立阈值 0.95）
        float[] query = {1.0f, 0.0f};
        float[] claimProto = {0.8f, 0.6f};  // 归一化后模=1，点积约 0.8
        // 手动归一化
        double norm = Math.sqrt(0.8 * 0.8 + 0.6 * 0.6);
        float[] claimNorm = {(float) (0.8 / norm), (float) (0.6 / norm)};

        when(embeddingService.encode(any())).thenReturn(query);
        when(prototypeStore.getAllPrototypes()).thenReturn(Map.of("claim_apply", claimNorm));

        assertThat(matcher.match("申请理赔")).isEmpty();
    }
}
