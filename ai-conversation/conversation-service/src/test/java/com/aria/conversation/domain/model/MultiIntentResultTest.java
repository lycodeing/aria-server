package com.aria.conversation.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MultiIntentResult 路由语义")
class MultiIntentResultTest {

    @Test
    @DisplayName("requiresTransfer: union语义 — 含COMPLAINT则为true")
    void requiresTransfer_anyComplaint_returnsTrue() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.COMPLAINT, "complaint", 1.0),
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.9)
        ), "RULE", 10L);
        assertThat(r.requiresTransfer()).isTrue();
    }

    @Test
    @DisplayName("requiresTransfer: 无转人工意图返回false")
    void requiresTransfer_noTransfer_returnsFalse() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.9)
        ), "RULE", 10L);
        assertThat(r.requiresTransfer()).isFalse();
    }

    @Test
    @DisplayName("skipRag: intersection语义 — 含FAQ_QUERY则不跳过")
    void skipRag_hasFaqQuery_returnsFalse() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.CHITCHAT, "chitchat", 0.9),
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.8)
        ), "RULE", 10L);
        assertThat(r.skipRag()).isFalse();
    }

    @Test
    @DisplayName("isEffectivelyOutOfScope: 全为 OUT_OF_SCOPE 或 UNKNOWN 返回true")
    void isEffectivelyOutOfScope_allOutOfScope_returnsTrue() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.OUT_OF_SCOPE, "out_of_scope", 0.9),
                new IntentResult(IntentType.UNKNOWN, "unknown", 0.0)
        ), "LLM", 300L);
        assertThat(r.isEffectivelyOutOfScope()).isTrue();
    }

    @Test
    @DisplayName("primaryIntent: COMPLAINT 优先于 FAQ_QUERY")
    void primaryIntent_complaintHigherPriorityThanFaq() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "faq_query", 0.9),
                new IntentResult(IntentType.COMPLAINT, "complaint", 0.85)
        ), "EMBEDDING", 35L);
        assertThat(r.primaryIntent().intent()).isEqualTo(IntentType.COMPLAINT);
    }

    @Test
    @DisplayName("intentCodes: 返回所有意图code列表")
    void intentCodes_returnsAllCodes() {
        var r = new MultiIntentResult(List.of(
                new IntentResult(IntentType.FAQ_QUERY, "query_order", 1.0),
                new IntentResult(IntentType.COMPLAINT, "complaint", 0.9)
        ), "RULE", 1L);
        assertThat(r.intentCodes()).containsExactlyInAnyOrder("query_order", "complaint");
    }

    @Test
    @DisplayName("UNKNOWN 兜底结果: primaryIntent 为 UNKNOWN 类型")
    void unknown_primaryIntent_isUnknownType() {
        assertThat(MultiIntentResult.UNKNOWN.primaryIntent().intent())
                .isEqualTo(IntentType.UNKNOWN);
    }
}
