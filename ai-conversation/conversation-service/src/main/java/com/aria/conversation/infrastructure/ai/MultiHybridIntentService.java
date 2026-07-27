package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.MultiIntentResult;
import com.aria.conversation.domain.service.MultiIntentService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
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
    private final MultiIntentClassifier llmClassifier;  // 依赖接口，不依赖具体实现（DIP）
    private final RoutingConfigProvider routingConfigProvider;
    private final MeterRegistry meterRegistry;

    @Override
    public MultiIntentResult classifyMulti(String userMessage) {
        long start = System.currentTimeMillis();
        try {
            return doClassify(userMessage, start);
        } catch (Exception e) {
            log.error("[MultiHybrid] 意图分类异常，降级 UNKNOWN. msg={}", userMessage, e);
            return MultiIntentResult.UNKNOWN;
        }
    }

    private MultiIntentResult doClassify(String userMessage, long start) {
        RoutingConfig.Intent cfg = routingConfigProvider.getConfig().getIntent();

        // 退化模式：multiIntentEnabled=false 时降为单意图
        if (!cfg.isMultiIntentEnabled()) {
            List<IntentResult> ruleResult = safeMatchAll(userMessage);
            IntentResult single = ruleResult.isEmpty() ? IntentResult.UNKNOWN : ruleResult.get(0);
            return new MultiIntentResult(List.of(single), ClassificationTierConstants.RULE, 0L);
        }

        // LinkedHashMap 保证插入顺序，同 intentCode 以先到的 Tier 为准
        Map<String, IntentResult> merged = new LinkedHashMap<>();
        String reachedTier = ClassificationTierConstants.RULE;

        // ── Tier 1: 规则层（必执行）──────────────────────────────────
        try {
            List<IntentResult> ruleResults = ruleMatcher.matchAll(userMessage);
            ruleResults.forEach(r -> merged.put(r.intentCode(), r));
            if (!ruleResults.isEmpty()) {
                log.debug("[MultiHybrid] Tier1 命中 {} 个意图", ruleResults.size());
            }
        } catch (Exception e) {
            log.warn("[MultiHybrid] Tier1 规则层异常，跳过. msg={}", userMessage, e);
        }

        // ── Tier 2: Embedding 原型层（embeddingEnabled=true 时始终执行）────
        if (cfg.isEmbeddingEnabled()) {
            try {
                List<IntentResult> embResults = embeddingMatcher.match(userMessage);
                // 必须在 putIfAbsent 前统计新增数量，否则统计恒为 0（I5 修复）
                long newCount = embResults.stream()
                        .filter(r -> !merged.containsKey(r.intentCode())).count();
                embResults.forEach(r -> merged.putIfAbsent(r.intentCode(), r));
                if (newCount > 0) {
                    // 只有 Tier2 真正新增了意图，才更新 reachedTier
                    reachedTier = ClassificationTierConstants.EMBEDDING;
                    log.debug("[MultiHybrid] Tier2 新增 {} 个意图", newCount);
                }
            } catch (Exception e) {
                log.warn("[MultiHybrid] Tier2 Embedding 层异常，跳过. msg={}", userMessage, e);
            }
        }

        // ── Tier 3: LLM 兜底（置信度不足时触发）──────────────────────
        if (shouldFallbackToLlm(merged, cfg)) {
            try {
                reachedTier = ClassificationTierConstants.LLM;
                List<IntentResult> llmResults = llmClassifier.classifyMulti(userMessage);
                // LLM 结果：仅补充，不覆盖 Tier1/Tier2 已有的高置信度结果
                llmResults.forEach(r -> merged.merge(r.intentCode(), r,
                        (ex, nr) -> ex.confidence() >= nr.confidence() ? ex : nr));
                log.debug("[MultiHybrid] Tier3 LLM 补充后共 {} 个意图", merged.size());
            } catch (Exception e) {
                log.warn("[MultiHybrid] Tier3 LLM 层异常，使用已有结果. msg={}", userMessage, e);
            }
        }

        List<IntentResult> finalResults = merged.isEmpty()
                ? List.of(IntentResult.UNKNOWN) : List.copyOf(merged.values());

        long elapsed = System.currentTimeMillis() - start;
        log.info("[MultiHybrid] 分类完成 tier={} intentCount={} cost={}ms",
                reachedTier, finalResults.size(), elapsed);

        recordMetrics(reachedTier, finalResults.size(), elapsed);
        return new MultiIntentResult(finalResults, reachedTier, elapsed);
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
