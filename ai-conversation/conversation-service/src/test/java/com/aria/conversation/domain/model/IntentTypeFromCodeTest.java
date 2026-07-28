package com.aria.conversation.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IntentType.fromCode 静态工厂")
class IntentTypeFromCodeTest {

    @Test
    @DisplayName("已知大写 code 正确解析")
    void fromCode_knownUpperCase_resolves() {
        assertThat(IntentType.fromCode("COMPLAINT")).isEqualTo(IntentType.COMPLAINT);
        assertThat(IntentType.fromCode("FAQ_QUERY")).isEqualTo(IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("小写 code 不区分大小写")
    void fromCode_lowerCase_resolves() {
        assertThat(IntentType.fromCode("transfer_request")).isEqualTo(IntentType.TRANSFER_REQUEST);
    }

    @Test
    @DisplayName("未知 code 返回 UNKNOWN（C4 修复：避免 LLM 幻觉意图被静默转为 FAQ_QUERY）")
    void fromCode_unknown_returnsUnknown() {
        assertThat(IntentType.fromCode("query_order")).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    @DisplayName("null 返回 UNKNOWN")
    void fromCode_null_returnsUnknown() {
        assertThat(IntentType.fromCode(null)).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    @DisplayName("空白字符串返回 UNKNOWN")
    void fromCode_blank_returnsUnknown() {
        assertThat(IntentType.fromCode("   ")).isEqualTo(IntentType.UNKNOWN);
    }

    // ── fromBusinessCode 测试（业务路由语境）────────────────────────────

    @Test
    @DisplayName("fromBusinessCode: 已知枚举 code 正确解析")
    void fromBusinessCode_knownCode_resolves() {
        assertThat(IntentType.fromBusinessCode("COMPLAINT")).isEqualTo(IntentType.COMPLAINT);
        assertThat(IntentType.fromBusinessCode("chitchat")).isEqualTo(IntentType.CHITCHAT);
    }

    @Test
    @DisplayName("fromBusinessCode: 自定义业务意图 code 返回 FAQ_QUERY（走 RAG 路径）")
    void fromBusinessCode_customBusinessCode_returnsFaqQuery() {
        // 自定义业务意图如 "query_order"、"claim_apply" 不在枚举中，走通用 FAQ_QUERY 路径
        assertThat(IntentType.fromBusinessCode("query_order")).isEqualTo(IntentType.FAQ_QUERY);
        assertThat(IntentType.fromBusinessCode("claim_apply")).isEqualTo(IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("fromBusinessCode: null 返回 FAQ_QUERY")
    void fromBusinessCode_null_returnsFaqQuery() {
        assertThat(IntentType.fromBusinessCode(null)).isEqualTo(IntentType.FAQ_QUERY);
    }
}
