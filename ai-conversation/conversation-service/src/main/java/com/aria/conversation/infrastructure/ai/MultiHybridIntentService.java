package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.MultiIntentResult;
import com.aria.conversation.domain.service.MultiIntentService;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 多意图三级级联协调器（@Primary），实现 {@link MultiIntentService}。
 *
 * <p>级联策略：
 * <pre>
 *   Tier 1: KeywordRegexIntentMatcher.matchAll()  [&lt;1ms, 必执行]
 *   Tier 2: EmbeddingPrototypeIntentMatcher        [~30ms, embeddingEnabled=true 时执行]
 *   Tier 3: MultiIntentClassifier（LLM）           [200-800ms, 置信度不足时兜底]
 * </pre>
 *
 * <p>高置信度自动积累通过 {@link IntentAccumulationService} 异步完成，
 * 使用独立 Bean 注入是正确拦截 {@code @Async} 代理的必要条件。
 *
 * <p>灰度开关：{@code multiIntentEnabled=false} 时退化为单意图，可无重启回滚。
 */
@Primary
@Component
@RequiredArgsConstructor
@Slf4j
public class MultiHybridIntentService implements MultiIntentService {

    private final KeywordRegexIntentMatcher ruleMatcher;
    private final EmbeddingPrototypeIntentMatcher embeddingMatcher;
    private final MultiIntentClassifier llmClassifier;
    private final RoutingConfigProvider routingConfigProvider;
    private final MeterRegistry meterRegistry;
    private final DomainRepository domainRepository;
    /** 独立 Bean，确保 @Async 代理正确拦截（不可改为 this. 自调用） */
    private final IntentAccumulationService accumulationService;

    @Override
    public MultiIntentResult classifyMulti(String userMessage) {
        long start = System.currentTimeMillis();
        try {
            return doClassify(userMessage, null, start);
        } catch (Exception e) {
            log.error("[MultiHybrid] 意图分类异常，降级 UNKNOWN. msg={}", userMessage, e);
            return MultiIntentResult.UNKNOWN;
        }
    }

    @Override
    public MultiIntentResult classifyMulti(String userMessage, String domainCode) {
        long start = System.currentTimeMillis();
        try {
            return doClassify(userMessage, domainCode, start);
        } catch (Exception e) {
            log.error("[MultiHybrid] 意图分类异常，降级 UNKNOWN. msg={}", userMessage, e);
            return MultiIntentResult.UNKNOWN;
        }
    }

    private MultiIntentResult doClassify(String userMessage, String domainCode, long start) {
        RoutingConfig.Intent cfg = routingConfigProvider.getConfig().getIntent();

        if (!cfg.isMultiIntentEnabled()) {
            List<IntentResult> ruleResult = safeMatchAll(userMessage);
            IntentResult single = ruleResult.isEmpty() ? IntentResult.UNKNOWN : ruleResult.get(0);
            return new MultiIntentResult(List.of(single), ClassificationTierConstants.RULE, 0L);
        }

        List<IntentConfig> mergedIntents = loadMergedIntents(domainCode);
        Map<String, IntentResult> merged = new LinkedHashMap<>();
        String reachedTier = ClassificationTierConstants.RULE;

        applyTier1(userMessage, merged);
        if (cfg.isEmbeddingEnabled() && applyTier2(userMessage, merged))
            reachedTier = ClassificationTierConstants.EMBEDDING;
        if (shouldFallbackToLlm(merged, cfg) && applyTier3(userMessage, mergedIntents, merged, cfg))
            reachedTier = ClassificationTierConstants.LLM;

        List<IntentResult> finalResults = merged.isEmpty()
                ? List.of(IntentResult.UNKNOWN) : List.copyOf(merged.values());
        long elapsed = System.currentTimeMillis() - start;
        // I3 修复：分类结果是高频日志，INFO 会在生产环境产生大量噪音，改为 DEBUG
        log.debug("[MultiHybrid] 分类完成 tier={} intentCount={} cost={}ms",
                reachedTier, finalResults.size(), elapsed);
        recordMetrics(reachedTier, finalResults.size(), elapsed);
        return new MultiIntentResult(finalResults, reachedTier, elapsed);
    }

    /** Tier1：规则层（必执行），命中结果写 merged。 */
    private void applyTier1(String userMessage, Map<String, IntentResult> merged) {
        try {
            List<IntentResult> results = ruleMatcher.matchAll(userMessage);
            results.forEach(r -> merged.put(r.intentCode(), r));
            if (!results.isEmpty()) log.debug("[MultiHybrid] Tier1 命中 {} 个意图", results.size());
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier1 规则层异常，跳过. msg={}", userMessage, e);
        }
    }

    /**
     * Tier2：Embedding 原型层，新增结果写入 merged。
     *
     * @return true 表示本层新增了至少一个意图（调用方据此更新 reachedTier）
     */
    private boolean applyTier2(String userMessage, Map<String, IntentResult> merged) {
        try {
            List<IntentResult> results = embeddingMatcher.match(userMessage);
            long newCount = results.stream().filter(r -> !merged.containsKey(r.intentCode())).count();
            results.forEach(r -> merged.putIfAbsent(r.intentCode(), r));
            if (newCount > 0) {
                log.debug("[MultiHybrid] Tier2 新增 {} 个意图", newCount);
                return true;
            }
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier2 Embedding 层异常，跳过. msg={}", userMessage, e);
        }
        return false;
    }

    /**
     * Tier3：LLM 兜底，补充结果写入 merged，并通过独立 Bean 异步积累高置信度案例。
     *
     * @return true 表示本层被触发（调用方据此更新 reachedTier）
     */
    private boolean applyTier3(String userMessage, List<IntentConfig> mergedIntents,
                                Map<String, IntentResult> merged, RoutingConfig.Intent cfg) {
        try {
            List<IntentResult> llmResults = llmClassifier.classifyMulti(userMessage, mergedIntents);
            llmResults.forEach(r -> merged.merge(r.intentCode(), r,
                    (ex, nr) -> ex.confidence() >= nr.confidence() ? ex : nr));
            log.debug("[MultiHybrid] Tier3 LLM 补充后共 {} 个意图", merged.size());
            // C1 修复：通过注入的独立 Bean 调用，Spring 代理正确拦截 @Async，不阻塞主路径
            accumulationService.asyncAccumulate(llmResults, userMessage, cfg);
            return true;
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier3 LLM 层异常，使用已有结果. msg={}", userMessage, e);
            return false;
        }
    }

    private boolean shouldFallbackToLlm(Map<String, IntentResult> merged, RoutingConfig.Intent cfg) {
        if (merged.isEmpty()) return true;
        if (merged.values().stream().anyMatch(IntentResult::requiresTransfer)) return false;
        double highConf = cfg.getEmbeddingHighConfidence();
        return merged.values().stream().noneMatch(r -> r.confidence() >= highConf);
    }

    private List<IntentResult> safeMatchAll(String message) {
        try {
            return ruleMatcher.matchAll(message);
        } catch (Exception e) {
            log.warn("[MultiHybrid] 退化模式规则层异常", e);
            return List.of();
        }
    }

    /**
     * 加载合并意图列表：{@code __system__} 路由级意图 + 活跃域业务意图。
     *
     * <p>去重规则：同 intentCode 以 {@code __system__} 域为准（路由优先级更高）。
     * {@code domainCode} 为 null 时只返回 {@code __system__} 意图。
     */
    private List<IntentConfig> loadMergedIntents(String domainCode) {
        List<IntentConfig> systemIntents = domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)
                .map(DomainConfig::intents)
                .orElse(List.of());

        if (domainCode == null || domainCode.isBlank()) {
            return systemIntents;
        }

        List<IntentConfig> domainIntents = domainRepository.findByCode(domainCode)
                .map(DomainConfig::intents)
                .orElse(List.of());

        if (domainIntents.isEmpty()) {
            return systemIntents;
        }

        // I2 修复：使用 import 而非 FQN（Set/ArrayList/Collectors 已在文件顶部 import）
        Set<String> systemCodes = systemIntents.stream()
                .map(IntentConfig::code)
                .collect(Collectors.toSet());

        List<IntentConfig> merged = new ArrayList<>(systemIntents);
        domainIntents.stream()
                .filter(i -> !systemCodes.contains(i.code()))
                .forEach(merged::add);

        log.debug("[MultiHybrid] 合并意图 system={} domain={} total={}",
                systemIntents.size(), domainIntents.size(), merged.size());
        return merged;
    }

    private void recordMetrics(String tier, int intentCount, long elapsedMs) {
        try {
            meterRegistry.counter("intent.classification.total",
                    "tier", tier, "intent_count", String.valueOf(intentCount)).increment();
            meterRegistry.timer("intent.classification.latency", "tier", tier)
                    .record(elapsedMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[MultiHybrid] Micrometer 指标记录失败（非关键）", e);
        }
    }
}
