package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 关键词 + 正则意图匹配器（Tier 1）。
 *
 * <p>启动时将所有意图的规则预编译缓存到内存，运行期纯内存匹配，无 IO 开销。
 * 命中置信度固定为 1.0；同时命中多个意图时取 sortOrder 最小（优先级最高）的那个。
 *
 * <p><b>ReDoS 防护：</b>patterns 由运营通过管理后台填写，保存时须在 API 层校验：
 * 长度 ≤ 200 字符，禁止嵌套量词（如 {@code (a+)+}）。
 *
 * <p><b>中文关键词说明：</b>纯子串匹配无词边界，关键词应至少 3 个汉字，
 * 高敏感意图（TRANSFER_REQUEST）建议使用正则而非单词。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordRegexIntentMatcher {

    private final DomainRepository domainRepository;

    /**
     * 编译后的规则条目，按 sortOrder 升序排列（DB 查询已排序，此处顺序保留）。
     * volatile + 整体替换保证可见性，无需锁。
     */
    private volatile List<IntentRuleEntry> compiledRules = List.of();

    @PostConstruct
    public void init() {
        reload();
    }

    /** 监听域配置变更事件，触发规则缓存刷新 */
    @EventListener
    public void onDomainEvicted(DomainCacheEvictedEvent event) {
        if (DomainCodes.SYSTEM_DOMAIN.equals(event.getDomainCode())) {
            log.info("[RuleMatcher] 检测到 __system__ 域配置变更，刷新意图规则缓存");
            reload();
        }
    }

    public void reload() {
        DomainConfig system = domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN).orElse(null);
        if (system == null) {
            log.warn("[RuleMatcher] __system__ 域不存在，规则层不可用");
            compiledRules = List.of();
            return;
        }
        List<IntentRuleEntry> entries = system.intents().stream()
                .filter(this::hasRules)
                .sorted(Comparator.comparingInt(IntentConfig::sortOrder))
                .map(this::compile)
                .toList();
        this.compiledRules = entries;
        log.info("[RuleMatcher] 加载意图规则 {} 条", entries.size());
    }

    /**
     * 尝试用规则匹配用户消息。
     * @return Optional.empty() 表示无命中，由下一层处理
     */
    public Optional<IntentResult> match(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return Optional.empty();
        String lower = userMessage.toLowerCase();
        for (IntentRuleEntry entry : compiledRules) {
            for (String kw : entry.keywords()) {
                if (lower.contains(kw.toLowerCase())) {
                    log.debug("[RuleMatcher] 关键词命中 intent={} keyword={}", entry.intentCode(), kw);
                    return Optional.of(new IntentResult(entry.intentType(), entry.intentCode().toLowerCase(), 1.0));
                }
            }
            for (Pattern p : entry.compiledPatterns()) {
                if (p.matcher(userMessage).find()) {
                    log.debug("[RuleMatcher] 正则命中 intent={} pattern={}", entry.intentCode(), p.pattern());
                    return Optional.of(new IntentResult(entry.intentType(), entry.intentCode().toLowerCase(), 1.0));
                }
            }
        }
        return Optional.empty();
    }

    private boolean hasRules(IntentConfig i) {
        return (i.keywords() != null && !i.keywords().isEmpty())
                || (i.patterns() != null && !i.patterns().isEmpty());
    }

    private IntentRuleEntry compile(IntentConfig i) {
        List<Pattern> compiled = i.patterns() == null ? List.of()
                : i.patterns().stream()
                        .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL))
                        .toList();
        // 自定义业务 code 不在枚举内时，视为 FAQ_QUERY 分叉（走 RAG+LLM）
        IntentType type;
        try {
            type = IntentType.valueOf(i.code().toUpperCase());
        } catch (IllegalArgumentException e) {
            type = IntentType.FAQ_QUERY;
        }
        return new IntentRuleEntry(
                i.code(), type,
                i.keywords() == null ? List.of() : i.keywords(),
                compiled);
    }

    record IntentRuleEntry(
            String intentCode,
            IntentType intentType,
            List<String> keywords,
            List<Pattern> compiledPatterns
    ) {}
}
