package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("KeywordRegexDomainMatcher")
class KeywordRegexDomainMatcherTest {

    @Mock private DomainRepository domainRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private KeywordRegexDomainMatcher matcher;

    private static DomainDO domain(String code, String keywords, String patterns) {
        DomainDO d = new DomainDO();
        d.setCode(code);
        d.setKeywords(keywords);
        d.setPatterns(patterns);
        return d;
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        matcher = new KeywordRegexDomainMatcher(domainRepository, objectMapper);
    }

    @Test
    @DisplayName("关键词命中：返回对应域 code")
    void matchDomain_keywordHit() {
        when(domainRepository.findAllEnabledSummary()).thenReturn(List.of(
                domain("finance", "[\"基金\",\"理财\"]", "[]")));
        matcher.reload();

        assertThat(matcher.matchDomain("我想买基金")).contains("finance");
    }

    @Test
    @DisplayName("正则命中：返回对应域 code")
    void matchDomain_patternHit() {
        when(domainRepository.findAllEnabledSummary()).thenReturn(List.of(
                domain("ecommerce", "[]", "[\".*退款.*\"]")));
        matcher.reload();

        assertThat(matcher.matchDomain("我要申请退款")).contains("ecommerce");
    }

    @Test
    @DisplayName("无命中：返回 empty")
    void matchDomain_noHit_empty() {
        when(domainRepository.findAllEnabledSummary()).thenReturn(List.of(
                domain("finance", "[\"基金\"]", "[]")));
        matcher.reload();

        assertThat(matcher.matchDomain("随便说一句话")).isEmpty();
    }
}
