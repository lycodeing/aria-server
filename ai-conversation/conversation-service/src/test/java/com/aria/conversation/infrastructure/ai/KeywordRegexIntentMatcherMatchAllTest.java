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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KeywordRegexIntentMatcher.matchAll 多规则命中")
class KeywordRegexIntentMatcherMatchAllTest {

    @Mock private DomainRepository domainRepository;
    private KeywordRegexIntentMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new KeywordRegexIntentMatcher(domainRepository);
    }

    private IntentConfig buildIntent(String code, List<String> keywords, int order) {
        return new IntentConfig(code, code, null, List.of(), false, false, null,
                List.of(), List.of(), keywords, List.of(), order);
    }

    private DomainConfig buildDomain(List<IntentConfig> intents) {
        return new DomainConfig(DomainCodes.SYSTEM_DOMAIN, "system", null, null, null, intents);
    }

    @Test
    @DisplayName("两条规则都命中：返回两个意图（不再首个返回）")
    void matchAll_twoRulesHit_returnsBoth() {
        var intents = List.of(
                buildIntent("COMPLAINT", List.of("投诉"), 1),
                buildIntent("FAQ_QUERY", List.of("查物流"), 2)
        );
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(buildDomain(intents)));

        var results = matcher.matchAll("我要投诉，同时查物流");

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(IntentResult::intent))
                .containsExactlyInAnyOrder(IntentType.COMPLAINT, IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("同一意图被多条规则命中：只返回一次（去重）")
    void matchAll_sameIntentTwoRules_deduplicates() {
        var intents = List.of(
                buildIntent("COMPLAINT", List.of("投诉"), 1),
                buildIntent("COMPLAINT", List.of("不满意"), 2)
        );
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(buildDomain(intents)));

        var results = matcher.matchAll("我投诉，非常不满意");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.COMPLAINT);
    }

    @Test
    @DisplayName("无命中：返回空列表")
    void matchAll_noHit_returnsEmpty() {
        var intents = List.of(buildIntent("COMPLAINT", List.of("投诉"), 1));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(buildDomain(intents)));

        assertThat(matcher.matchAll("查一下我的订单")).isEmpty();
    }

    @Test
    @DisplayName("空白消息：返回空列表，不抛异常")
    void matchAll_blank_returnsEmpty() {
        assertThat(matcher.matchAll("  ")).isEmpty();
    }

    @Test
    @DisplayName("match() 兼容性：仍返回 Optional，取 sortOrder 最小的命中")
    void match_backwardsCompatible_returnsFirstHit() {
        var intents = List.of(
                buildIntent("COMPLAINT", List.of("投诉"), 1),
                buildIntent("FAQ_QUERY", List.of("查物流"), 2)
        );
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN))
                .thenReturn(Optional.of(buildDomain(intents)));

        var result = matcher.match("我要投诉，同时查物流");

        assertThat(result).isPresent();
        assertThat(result.get().intent()).isEqualTo(IntentType.COMPLAINT);
    }
}
