package com.aria.conversation.infrastructure.observability;

import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * {@link RagQualityRecorder} 的 miss 判定与阈值逻辑单测。
 *
 * <p>此前评审曾发现阈值经构造 {@code @Value} 注入静默取 0.0 的缺陷（导致全部判 miss），
 * 本测试锁定「字段注入阈值 + isMiss 边界」的正确行为，防止回归。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagQualityRecorder miss 判定")
class RagQualityRecorderTest {

    @Mock private RagMissLogMapper missLogMapper;
    @Captor private ArgumentCaptor<RagMissLogEntity> entityCaptor;

    private RagQualityRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new RagQualityRecorder(missLogMapper);
        // 模拟 @Value 注入：字段注入阈值为 0.5
        ReflectionTestUtils.setField(recorder, "missThreshold", 0.5);
    }

    private static KnowledgeSearchResult.Hit hit(double score, String source) {
        KnowledgeSearchResult.Hit h = new KnowledgeSearchResult.Hit();
        h.setScore(score);
        h.setSource(source);
        return h;
    }

    @Test
    @DisplayName("空命中 → isMiss=true，top1Score/source 为 null，hitCount=0")
    void emptyHits_isMiss() {
        recorder.logAsync("s1", "q", List.of(), null, null);

        verify(missLogMapper).insert(entityCaptor.capture());
        RagMissLogEntity e = entityCaptor.getValue();
        assertThat(e.getIsMiss()).isTrue();
        assertThat(e.getTop1Score()).isNull();
        assertThat(e.getSource()).isNull();
        assertThat(e.getHitCount()).isZero();
    }

    @Test
    @DisplayName("top1 分数低于阈值 → isMiss=true")
    void top1BelowThreshold_isMiss() {
        recorder.logAsync("s1", "q", List.of(hit(0.3, "VECTOR")), null, null);

        verify(missLogMapper).insert(entityCaptor.capture());
        RagMissLogEntity e = entityCaptor.getValue();
        assertThat(e.getIsMiss()).isTrue();
        assertThat(e.getTop1Score()).isEqualTo(0.3);
        assertThat(e.getHitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("top1 分数不低于阈值 → isMiss=false")
    void top1AtOrAboveThreshold_notMiss() {
        recorder.logAsync("s1", "q", List.of(hit(0.72, "RERANK"), hit(0.4, "VECTOR")), "domain_a",
                List.of("faq_query", "cancel_order"));

        verify(missLogMapper).insert(entityCaptor.capture());
        RagMissLogEntity e = entityCaptor.getValue();
        assertThat(e.getIsMiss()).isFalse();
        assertThat(e.getTop1Score()).isEqualTo(0.72);
        assertThat(e.getSource()).isEqualTo("RERANK");
        assertThat(e.getHitCount()).isEqualTo(2);
        assertThat(e.getDomainCode()).isEqualTo("domain_a");
        assertThat(e.getIntentCodes()).isEqualTo("faq_query,cancel_order");
    }
}
