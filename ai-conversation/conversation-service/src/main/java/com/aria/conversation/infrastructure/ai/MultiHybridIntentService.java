package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.model.MultiIntentResult;
import com.aria.conversation.domain.service.MultiIntentService;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.aria.conversation.infrastructure.example.IntentExampleVectorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    private final IntentExampleVectorRepository exampleVectorRepo;
    private final EmbeddingService embeddingService;
    private final DomainRepository domainRepository;  // 加载活跃域意图，合并后传给 Tier3

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
        if (cfg.isEmbeddingEnabled() && applyTier2(userMessage, merged)) reachedTier = ClassificationTierConstants.EMBEDDING;
        if (shouldFallbackToLlm(merged, cfg) && applyTier3(userMessage, mergedIntents, merged, cfg)) reachedTier = ClassificationTierConstants.LLM;

        List<IntentResult> finalResults = merged.isEmpty()
                ? List.of(IntentResult.UNKNOWN) : List.copyOf(merged.values());
        long elapsed = System.currentTimeMillis() - start;
        log.info("[MultiHybrid] 分类完成 tier={} intentCount={} cost={}ms", reachedTier, finalResults.size(), elapsed);
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
     * Tier3：LLM 兜底，补充结果写入 merged，并异步积累高置信度案例。
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
            autoAccumulate(llmResults, userMessage, cfg);
            return true;
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier3 LLM 层异常，使用已有结果. msg={}", userMessage, e);
            return false;
        }
    }

    /**
     * 判断是否需要降级到 Tier3 LLM。
     *
     * <p>满足以下任一条件则跳过 LLM：
     * <ul>
     *   <li>已有需要转人工的意图（最紧急，不需要 LLM 确认）</li>
     *   <li>已有置信度 >= embeddingHighConfidence 的意图</li>
     * </ul>
     */
    private boolean shouldFallbackToLlm(Map<String, IntentResult> merged,
                                         RoutingConfig.Intent cfg) {
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

        // 合并：以 intentCode 去重，__system__ 的优先（路由级）
        java.util.Set<String> systemCodes = systemIntents.stream()
                .map(IntentConfig::code)
                .collect(java.util.stream.Collectors.toSet());

        List<IntentConfig> merged = new java.util.ArrayList<>(systemIntents);
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

    /**
     * 将 Tier3 LLM 高置信度结果异步积累到历史案例库（数据飞轮）。
     *
     * <p>{@code @Async} 在独立线程执行，不阻塞主路径延迟。
     * {@link IntentExampleVectorRepository#saveIfAbsent} 底层 ON CONFLICT DO NOTHING，幂等安全。
     */
    @Async("prototypeRebuildExecutor")
    protected void autoAccumulate(List<IntentResult> llmResults,
                                   String userMessage,
                                   RoutingConfig.Intent cfg) {
        if (!cfg.isAutoAccumulateEnabled()) {
            return;
        }
        double threshold = cfg.getAutoAccumulateMinConfidence();
        for (IntentResult r : llmResults) {
            if (r.intent() == IntentType.UNKNOWN || r.confidence() < threshold) {
                continue;
            }
            try {
                float[] embedding = embeddingService.encode(userMessage);
                exampleVectorRepo.saveIfAbsent(r.intentCode(), userMessage, embedding, true);
                log.debug("[AutoAccumulate] 积累案例 intentCode={} confidence={}",
                        r.intentCode(), String.format("%.3f", r.confidence()));
                meterRegistry.counter("intent.example.accumulate.total",
                        "intent_code", r.intentCode()).increment();
            } catch (Exception e) {
                log.warn("[AutoAccumulate] 积累失败 intentCode={}", r.intentCode(), e);
            }
        }
    }
}
