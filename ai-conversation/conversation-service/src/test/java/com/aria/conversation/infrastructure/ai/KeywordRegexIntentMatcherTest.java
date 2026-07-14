package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("KeywordRegexIntentMatcher")
class KeywordRegexIntentMatcherTest {

    @Mock private DomainRepository domainRepository;
    private KeywordRegexIntentMatcher matcher;

    private static IntentConfig intentConfig(String code, List<String> keywords, List<String> patterns, int sortOrder) {
        return new IntentConfig(code, code, "desc", List.of(), false, false, null,
                List.of(), List.of(), keywords, patterns, sortOrder);
    }

    private DomainConfig systemDomain(IntentConfig... intents) {
        return new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "系统", null, null, null, List.of(intents));
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 每个测试用新的 matcher 实例，避免 Caffeine 缓存跨测试污染
        matcher = new KeywordRegexIntentMatcher(domainRepository);
    }

    @Test
    @DisplayName("关键词命中：消息包含关键词，返回对应意图")
    void match_keywordHit() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("TRANSFER_REQUEST", List.of("转人工", "找真人"), List.of(), 1))));

        Optional<IntentResult> result = matcher.match("我想转人工处理一下");

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo(IntentType.TRANSFER_REQUEST);
        assertThat(result.get().intentCode()).isEqualTo("transfer_request");
        assertThat(result.get().confidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("关键词大小写不敏感")
    void match_keyword_caseInsensitive() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("FAQ_QUERY", List.of("FAQ"), List.of(), 0))));

        assertThat(matcher.match("我有个faq问题")).isPresent();
    }

    @Test
    @DisplayName("正则命中：pattern 匹配，返回对应意图")
    void match_patternHit() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("COMPLAINT", List.of(), List.of("^.*投诉.*"), 0))));

        Optional<IntentResult> result = matcher.match("我要投诉你们！");

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo(IntentType.COMPLAINT);
    }

    @Test
    @DisplayName("无规则配置时返回 empty")
    void match_noRules_returnsEmpty() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("FAQ_QUERY", List.of(), List.of(), 0))));

        assertThat(matcher.match("随便说一句话")).isEmpty();
    }

    @Test
    @DisplayName("多意图冲突：按 sortOrder 取优先级最高的（sortOrder 最小）")
    void match_multipleHits_returnLowestSortOrder() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("TRANSFER_REQUEST", List.of("人工"), List.of(), 1),
                        intentConfig("COMPLAINT", List.of("不满意"), List.of(), 2))));

        // Message contains BOTH "人工" (→ TRANSFER_REQUEST, sortOrder=1) AND "不满意" (→ COMPLAINT, sortOrder=2)
        // Since rules list is ordered by sortOrder, TRANSFER_REQUEST (order=1) should be checked first and win
        Optional<IntentResult> result = matcher.match("我不满意，要找人工客服");

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo(IntentType.TRANSFER_REQUEST);
        assertThat(result.get().intentCode()).isEqualTo("transfer_request");
    }

    @Test
    @DisplayName("自定义业务 code 不在枚举内，intent 降级为 FAQ_QUERY")
    void match_customCode_fallbackToFaqQuery() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(systemDomain(
                        intentConfig("query_order", List.of("查订单"), List.of(), 0))));

        Optional<IntentResult> result = matcher.match("帮我查订单状态");

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo(IntentType.FAQ_QUERY);
        assertThat(result.get().intentCode()).isEqualTo("query_order");
    }

    @Test
    @DisplayName("__system__ 域不存在时不抛异常，match 返回 empty")
    void reload_domainNotFound_noException() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.empty());

        assertThat(matcher.match("任意消息")).isEmpty();
    }
}
