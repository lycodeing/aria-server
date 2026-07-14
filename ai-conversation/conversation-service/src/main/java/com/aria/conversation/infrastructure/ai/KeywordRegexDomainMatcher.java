package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 关键词 + 正则域路由匹配器（Tier 1）。
 * 启动时全量加载所有启用域的规则到内存，监听 DomainCacheEvictedEvent 自动刷新。
 */
@Slf4j
@Component
public class KeywordRegexDomainMatcher {

    private final DomainRepository domainRepository;
    private final ObjectMapper objectMapper;
    private volatile List<DomainRuleEntry> compiledRules = List.of();

    /** Spring 正式构造（通过 @Component 自动注入）*/
    public KeywordRegexDomainMatcher(DomainRepository domainRepository, ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.objectMapper = objectMapper;
    }

    /** 仅供单元测试使用，使用默认 ObjectMapper */
    KeywordRegexDomainMatcher(DomainRepository domainRepository) {
        this(domainRepository, new ObjectMapper());
    }

    @PostConstruct
    public void init() {
        reload();
    }

    @EventListener
    public void onDomainEvicted(DomainCacheEvictedEvent event) {
        log.info("[DomainRuleMatcher] 域 {} 配置变更，刷新域规则缓存", event.getDomainCode());
        reload();
    }

    public void reload() {
        List<DomainRuleEntry> entries = domainRepository.findAllEnabledSummary().stream()
                .filter(this::hasRules)
                .map(this::compile)
                .toList();
        this.compiledRules = entries;
        log.info("[DomainRuleMatcher] 加载域规则 {} 条", entries.size());
    }

    /**
     * 用关键词/正则匹配用户消息，命中则返回对应域 code。
     *
     * @return Optional.empty() 表示未命中，继续走 LLM 路由
     */
    public Optional<String> matchDomain(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        String lower = userMessage.toLowerCase();
        for (DomainRuleEntry entry : compiledRules) {
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

    private boolean hasRules(DomainDO d) {
        return (d.getKeywords() != null && !d.getKeywords().isBlank() && !d.getKeywords().equals("[]"))
                || (d.getPatterns() != null && !d.getPatterns().isBlank() && !d.getPatterns().equals("[]"));
    }

    private DomainRuleEntry compile(DomainDO d) {
        List<String> kws = parseJsonArray(d.getKeywords());
        List<Pattern> pats = parseJsonArray(d.getPatterns()).stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
                .toList();
        return new DomainRuleEntry(d.getCode(), kws, pats);
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[DomainRuleMatcher] JSON 数组解析失败: {}", json);
            return List.of();
        }
    }

    record DomainRuleEntry(String domainCode, List<String> keywords, List<Pattern> compiledPatterns) {}
}
