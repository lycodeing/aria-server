package com.aria.conversation.infrastructure.observability;

import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link LlmCostLogger} 单测：null usage 跳过 + 正常字段映射。
 *
 * <p>OpenAI 兼容端点在未开启 include_usage 时流式响应可能返回 null usage，
 * 此时必须静默跳过而非抛异常/写脏行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LlmCostLogger Token 成本落库")
class LlmCostLoggerTest {

    @Mock private LlmCostLogMapper mapper;
    @Captor private ArgumentCaptor<LlmCostLogEntity> captor;

    private LlmCostLogger logger;

    @BeforeEach
    void setUp() {
        logger = new LlmCostLogger(mapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    @DisplayName("usage 为 null → 跳过写入，不抛异常")
    void nullUsage_skipsInsert() {
        logger.logAsync("s1", "gpt-4o", "CHAT", null, 120L);
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(LlmCostLogEntity.class));
    }

    @Test
    @DisplayName("usage 非 null → 正确映射 token 字段与耗时")
    void withUsage_mapsFields() {
        TokenUsage usage = new TokenUsage(100, 50);
        logger.logAsync("s1", "gpt-4o", "INTENT_CLASSIFY", usage, 300L);

        verify(mapper).insert(captor.capture());
        LlmCostLogEntity e = captor.getValue();
        assertThat(e.getSessionId()).isEqualTo("s1");
        assertThat(e.getModelName()).isEqualTo("gpt-4o");
        assertThat(e.getCallType()).isEqualTo("INTENT_CLASSIFY");
        assertThat(e.getInputTokens()).isEqualTo(100);
        assertThat(e.getOutputTokens()).isEqualTo(50);
        assertThat(e.getTotalTokens()).isEqualTo(150);
        assertThat(e.getLatencyMs()).isEqualTo(300);
    }
}
