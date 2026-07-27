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
    @DisplayName("未知 code 返回 FAQ_QUERY 兜底")
    void fromCode_unknown_returnsFaqQuery() {
        assertThat(IntentType.fromCode("query_order")).isEqualTo(IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("null 返回 FAQ_QUERY 兜底")
    void fromCode_null_returnsFaqQuery() {
        assertThat(IntentType.fromCode(null)).isEqualTo(IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("空白字符串返回 FAQ_QUERY 兜底")
    void fromCode_blank_returnsFaqQuery() {
        assertThat(IntentType.fromCode("   ")).isEqualTo(IntentType.FAQ_QUERY);
    }
}
