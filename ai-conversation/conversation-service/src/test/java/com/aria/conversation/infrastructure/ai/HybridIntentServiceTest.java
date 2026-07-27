package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.model.MultiIntentResult;
import com.aria.conversation.domain.service.MultiIntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * HybridIntentService 向后兼容测试。
 *
 * <p>改造后 HybridIntentService 代理 MultiIntentService，
 * 取 primaryIntent() 返回，对 IntentService 调用方保持零感知兼容。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HybridIntentService 向后兼容代理")
class HybridIntentServiceTest {

    @Mock private MultiIntentService multiIntentService;
    private HybridIntentService service;

    @BeforeEach
    void setUp() {
        service = new HybridIntentService(multiIntentService);
    }

    private MultiIntentResult singleResult(IntentType type, String code, double conf) {
        return new MultiIntentResult(
                List.of(new IntentResult(type, code, conf)), "RULE", 1L);
    }

    @Test
    @DisplayName("classify: 代理 MultiIntentService，返回 primaryIntent")
    void classify_delegatesToMultiIntentService_returnsPrimaryIntent() {
        when(multiIntentService.classifyMulti("转人工"))
                .thenReturn(singleResult(IntentType.TRANSFER_REQUEST, "transfer_request", 1.0));

        var result = service.classify("转人工");

        assertThat(result.intent()).isEqualTo(IntentType.TRANSFER_REQUEST);
        assertThat(result.confidence()).isEqualTo(1.0);
        verify(multiIntentService).classifyMulti("转人工");
    }

    @Test
    @DisplayName("classify: 多意图时取优先级最高的主意图（COMPLAINT > FAQ_QUERY）")
    void classify_multiIntent_returnsPrimaryByPriority() {
        when(multiIntentService.classifyMulti("投诉加查物流"))
                .thenReturn(new MultiIntentResult(List.of(
                        new IntentResult(IntentType.FAQ_QUERY, "query_logistics", 0.9),
                        new IntentResult(IntentType.COMPLAINT, "complaint", 0.85)
                ), "RULE", 1L));

        var result = service.classify("投诉加查物流");

        // COMPLAINT 优先级高于 FAQ_QUERY，取 COMPLAINT
        assertThat(result.intent()).isEqualTo(IntentType.COMPLAINT);
    }

    @Test
    @DisplayName("classify: MultiIntentService 返回 UNKNOWN，透传给调用方")
    void classify_unknownResult_returnsUnknown() {
        when(multiIntentService.classifyMulti(anyString()))
                .thenReturn(MultiIntentResult.UNKNOWN);

        var result = service.classify("随机文本");

        assertThat(result.intent()).isEqualTo(IntentType.UNKNOWN);
    }
}
