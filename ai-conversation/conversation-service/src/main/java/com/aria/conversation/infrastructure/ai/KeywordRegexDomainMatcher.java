package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 关键词/正则域路由规则匹配器（Tier 1）。
 *
 * <p>规则列表通过 Caffeine 本地缓存维护（TTL 5 分钟），
 * 与 {@link KeywordRegexIntentMatcher} 保持相同的缓存策略，
 * 运营修改配置后最多 5 分钟内自动生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRegexDomainMatcher {

    /** 领域配置仓储，提供所有启用域的 keywords/patterns 字段 */
    private final DomainRepository domainRepository;
    /** JSON 解析工具，将 keywords/patterns JSONB 字符串解析为 List<String> */
    private final ObjectMapper     objectMapper;

    /**
     * Caffeine 本地缓存，存储编译后的域规则列表，TTL 5 分钟，单条记录。
     */
    private final Cache<String, List<DomainRuleEntry>> rulesCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    /**
     * 尝试用规则匹配用户消息，返回命中的域 code。
     *
     * @param userMessage 用户消息，null 或空白直接返回 empty
     * @return 命中的域 code，未命中返回 empty（继续走 LLM 路由）
     */
    public Optional<String> matchDomain(String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            return Optional.empty();
        }
        String lower = userMessage.toLowerCase();
        for (DomainRuleEntry entry : loadRules()) {
            for (String kw : entry.keywords()) {
                if (lower.contains(kw.toLowerCase())) {
                    return Optional.of(entry.domainCode());
                }
            }
            for (Pattern p : entry.compiledPatterns()) {
                if (p.matcher(userMessage).find()) {
                    return Optional.of(entry.domainCode());
                }
            }
        }
        return Optional.empty();
    }

    private List<DomainRuleEntry> loadRules() {
        return rulesCache.get(CustomerServiceCacheConstant.DOMAIN_RULES, k -> {
            List<DomainRuleEntry> rules = domainRepository.findAllEnabledSummary().stream()
                    .filter(this::hasRules)
                    .map(this::compile)
                    .toList();
            log.info("[DomainRuleMatcher] 加载域规则 {} 条", rules.size());
            return rules;
        });
    }

    private boolean hasRules(DomainDO d) {
        return (d.getKeywords() != null && !d.getKeywords().isBlank() && !d.getKeywords().equals("[]"))
                || (d.getPatterns() != null && !d.getPatterns().isBlank() && !d.getPatterns().equals("[]"));
    }

    private DomainRuleEntry compile(DomainDO d) {
        return new DomainRuleEntry(d.getCode(), parseJson(d.getKeywords()),
                parseJson(d.getPatterns()).stream()
                        .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
                        .toList());
    }

    private List<String> parseJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[DomainRuleMatcher] JSON 解析失败: {}", json);
            return List.of();
        }
    }

    record DomainRuleEntry(String domainCode, List<String> keywords, List<Pattern> compiledPatterns) {}
}
