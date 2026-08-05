package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.MultiIntentResult;
import com.aria.conversation.domain.service.MultiIntentService;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.observability.IntentMetricsRecorder;
import com.aria.conversation.infrastructure.observability.IntentTierStatEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final IntentMetricsRecorder metricsRecorder;
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

        // 每层记录：执行/命中/耗时，供 Micrometer 实时指标与明细落库共同使用
        IntentTierStatEntity.IntentTierStatEntityBuilder detail = IntentTierStatEntity.builder()
                .domainCode(domainCode);

        TierOutcome t1 = applyTier1(userMessage, merged);
        detail.tier1Hit(t1.hit()).tier1LatencyMs((int) t1.elapsedMs());

        if (cfg.isEmbeddingEnabled()) {
            TierOutcome t2 = applyTier2(userMessage, merged);
            detail.tier2Executed(true).tier2Hit(t2.hit()).tier2LatencyMs((int) t2.elapsedMs());
            if (t2.hit()) reachedTier = ClassificationTierConstants.EMBEDDING;
        }

        if (shouldFallbackToLlm(merged, cfg)) {
            TierOutcome t3 = applyTier3(userMessage, mergedIntents, merged, cfg);
            detail.tier3Executed(true).tier3Hit(t3.hit()).tier3LatencyMs((int) t3.elapsedMs());
            if (t3.hit()) reachedTier = ClassificationTierConstants.LLM;
        }

        List<IntentResult> finalResults = merged.isEmpty()
                ? List.of(IntentResult.UNKNOWN) : List.copyOf(merged.values());
        long elapsed = System.currentTimeMillis() - start;
        // I3 修复：分类结果是高频日志，INFO 会在生产环境产生大量噪音，改为 DEBUG
        log.debug("[MultiHybrid] 分类完成 tier={} intentCount={} cost={}ms",
                reachedTier, finalResults.size(), elapsed);
        metricsRecorder.recordClassification(reachedTier, finalResults.size(), elapsed);
        // 明细异步落库，供 Admin 命中率报表聚合（写失败不影响主流程）
        metricsRecorder.persistDetail(detail.reachedTier(reachedTier).build());
        return new MultiIntentResult(finalResults, reachedTier, elapsed);
    }

    /** 单层执行结果：是否命中 + 本层耗时（毫秒）。 */
    private record TierOutcome(boolean hit, long elapsedMs) {}

    /** Tier1：规则层（必执行），命中结果写 merged。 */
    private TierOutcome applyTier1(String userMessage, Map<String, IntentResult> merged) {
        long tierStart = System.currentTimeMillis();
        boolean hit = false;
        try {
            List<IntentResult> results = ruleMatcher.matchAll(userMessage);
            results.forEach(r -> merged.put(r.intentCode(), r));
            hit = !results.isEmpty();
            if (hit) log.debug("[MultiHybrid] Tier1 命中 {} 个意图", results.size());
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier1 规则层异常，跳过. msg={}", userMessage, e);
        }
        long elapsed = System.currentTimeMillis() - tierStart;
        metricsRecorder.recordTier(ClassificationTierConstants.RULE, hit, elapsed);
        return new TierOutcome(hit, elapsed);
    }

    /**
     * Tier2：Embedding 原型层，新增结果写入 merged。
     *
     * @return 本层是否新增意图 + 耗时（hit=true 时调用方更新 reachedTier）
     */
    private TierOutcome applyTier2(String userMessage, Map<String, IntentResult> merged) {
        long tierStart = System.currentTimeMillis();
        boolean hit = false;
        try {
            List<IntentResult> results = embeddingMatcher.match(userMessage);
            long newCount = results.stream().filter(r -> !merged.containsKey(r.intentCode())).count();
            results.forEach(r -> merged.putIfAbsent(r.intentCode(), r));
            hit = newCount > 0;
            if (hit) log.debug("[MultiHybrid] Tier2 新增 {} 个意图", newCount);
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier2 Embedding 层异常，跳过. msg={}", userMessage, e);
        }
        long elapsed = System.currentTimeMillis() - tierStart;
        metricsRecorder.recordTier(ClassificationTierConstants.EMBEDDING, hit, elapsed);
        return new TierOutcome(hit, elapsed);
    }

    /**
     * Tier3：LLM 兜底，补充结果写入 merged，并通过独立 Bean 异步积累高置信度案例。
     *
     * @return 本层是否被成功触发 + 耗时（hit=true 时调用方更新 reachedTier）
     */
    private TierOutcome applyTier3(String userMessage, List<IntentConfig> mergedIntents,
                                Map<String, IntentResult> merged, RoutingConfig.Intent cfg) {
        long tierStart = System.currentTimeMillis();
        boolean hit = false;
        try {
            List<IntentResult> llmResults = llmClassifier.classifyMulti(userMessage, mergedIntents);
            llmResults.forEach(r -> merged.merge(r.intentCode(), r,
                    (ex, nr) -> ex.confidence() >= nr.confidence() ? ex : nr));
            log.debug("[MultiHybrid] Tier3 LLM 补充后共 {} 个意图", merged.size());
            // C1 修复：通过注入的独立 Bean 调用，Spring 代理正确拦截 @Async，不阻塞主路径
            accumulationService.asyncAccumulate(llmResults, userMessage, cfg);
            hit = true;
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier3 LLM 层异常，使用已有结果. msg={}", userMessage, e);
        }
        long elapsed = System.currentTimeMillis() - tierStart;
        metricsRecorder.recordTier(ClassificationTierConstants.LLM, hit, elapsed);
        return new TierOutcome(hit, elapsed);
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

}
