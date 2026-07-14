package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.config.CustomerServiceCacheConstant;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 关键词/正则意图规则匹配器（Tier 1）。
 *
 * <p>规则列表通过 Caffeine 本地缓存维护（TTL 5 分钟），
 * 运营修改配置后最多 5 分钟内自动生效，无需手动刷新或重启。
 *
 * <p><b>ReDoS 防护：</b>patterns 在管理后台 API 层由 {@link com.aria.conversation.interfaces.rest.validation.ValidRegexPatterns}
 * 校验合法性（长度 ≤ 200 字符，禁止嵌套量词），本类不重复校验。
 *
 * <p><b>中文关键词说明：</b>纯子串匹配，建议关键词至少 3 个汉字，
 * 高敏感意图（TRANSFER_REQUEST）建议使用正则而非短关键词。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRegexIntentMatcher {

    /** 领域配置仓储，提供 __system__ 域意图列表（含 keywords / patterns / sortOrder 字段） */
    private final DomainRepository domainRepository;

    /**
     * Caffeine 本地缓存，存储编译后的意图规则列表，TTL 5 分钟，单条记录。
     * TTL 过期后由 Caffeine 自动触发 loadRules() 重新拉取，与 RoutingConfigProvider 策略统一。
     */
    private final Cache<String, List<IntentRuleEntry>> rulesCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(1)
            .build();

    /**
     * 尝试用规则匹配用户消息（纯内存操作，< 1ms）。
     *
     * <p>遍历规则列表（已按 sortOrder 升序排列），依次执行关键词包含匹配和正则匹配，
     * 第一个命中即返回，不继续匹配剩余规则。
     *
     * @param userMessage 用户消息，null 或空白直接返回 empty
     * @return 命中返回 {@link IntentResult}（confidence=1.0，intentCode 小写），未命中返回 empty
     */
    public Optional<IntentResult> match(String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            return Optional.empty();
        }
        String lower = userMessage.toLowerCase();
        for (IntentRuleEntry entry : loadRules()) {
            for (String kw : entry.keywords()) {
                if (lower.contains(kw.toLowerCase())) {
                    log.debug("[RuleMatcher] 关键词命中 intent={} kw={}", entry.intentCode(), kw);
                    return Optional.of(new IntentResult(entry.intentType(),
                            entry.intentCode().toLowerCase(), 1.0));
                }
            }
            for (Pattern p : entry.compiledPatterns()) {
                if (p.matcher(userMessage).find()) {
                    log.debug("[RuleMatcher] 正则命中 intent={} pattern={}", entry.intentCode(), p.pattern());
                    return Optional.of(new IntentResult(entry.intentType(),
                            entry.intentCode().toLowerCase(), 1.0));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 从 Caffeine 缓存加载规则列表，缓存未命中时从 __system__ 域拉取并编译。
     *
     * @return 编译后的规则条目列表，按 sortOrder 升序排列，不可修改
     */
    private List<IntentRuleEntry> loadRules() {
        return rulesCache.get(CustomerServiceCacheConstant.INTENT_RULES, k -> {
            DomainConfig system = domainRepository
                    .findByCode(DomainCodes.SYSTEM_DOMAIN).orElse(null);
            if (system == null) {
                log.warn("[RuleMatcher] __system__ 域不存在，规则层不可用");
                return List.of();
            }
            List<IntentRuleEntry> rules = system.intents().stream()
                    .filter(this::hasRules)
                    .sorted(Comparator.comparingInt(IntentConfig::sortOrder))
                    .map(this::compile)
                    .toList();
            log.info("[RuleMatcher] 加载意图规则 {} 条", rules.size());
            return rules;
        });
    }

    private boolean hasRules(IntentConfig i) {
        return (i.keywords() != null && !i.keywords().isEmpty())
                || (i.patterns() != null && !i.patterns().isEmpty());
    }

    private IntentRuleEntry compile(IntentConfig i) {
        List<Pattern> patterns = i.patterns() == null ? List.of()
                : i.patterns().stream()
                        .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
                        .toList();
        IntentType type;
        try {
            type = IntentType.valueOf(i.code().toUpperCase());
        } catch (IllegalArgumentException e) {
            type = IntentType.FAQ_QUERY;
        }
        return new IntentRuleEntry(i.code(), type,
                i.keywords() == null ? List.of() : i.keywords(), patterns);
    }

    record IntentRuleEntry(
            String intentCode, IntentType intentType,
            List<String> keywords, List<Pattern> compiledPatterns
    ) {}
}
