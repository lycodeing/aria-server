package com.aria.conversation.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * DIT 意图识别可观测性记录器。
 *
 * <p>集中管理所有意图相关指标名与 tag key，业务类（如 {@code MultiHybridIntentService}、
 * {@code SessionFeedbackAppService}）不再直接操作 {@link MeterRegistry}，调用侧只需一行。
 *
 * <p>两类职责：
 * <ul>
 *   <li>Micrometer 指标：{@code /actuator/metrics} 的 JVM 级实时观测（命中率、延迟）；</li>
 *   <li>明细落库：{@link #persistDetail} 异步写 {@code cs_intent_tier_stat}，
 *       作为管理台历史趋势与命中率报表的数据源。</li>
 * </ul>
 */
@Slf4j
@Component
public class IntentMetricsRecorder {

    private static final String METRIC_TIER_HIT      = "intent.tier.hit.total";
    private static final String METRIC_TIER_LATENCY  = "intent.tier.latency";
    private static final String METRIC_FEEDBACK      = "intent.feedback.total";
    private static final String METRIC_CLASSIFY_TOTAL   = "intent.classification.total";
    private static final String METRIC_CLASSIFY_LATENCY = "intent.classification.latency";
    private static final String TAG_TIER = "tier";
    private static final String TAG_HIT  = "hit";
    private static final String TAG_TYPE = "type";
    private static final String TAG_INTENT_COUNT = "intent_count";

    private final MeterRegistry registry;
    private final IntentTierStatMapper tierStatMapper;

    public IntentMetricsRecorder(MeterRegistry registry, IntentTierStatMapper tierStatMapper) {
        this.registry = registry;
        this.tierStatMapper = tierStatMapper;
    }

    /**
     * 记录某一层的执行结果（Micrometer 实时指标）。
     *
     * @param tier      层标识，取 {@code ClassificationTierConstants} 中的常量
     * @param hit       true = 本层有命中意图；false = 未命中，将级联下一层
     * @param elapsedMs 本层实际耗时（毫秒）
     */
    public void recordTier(String tier, boolean hit, long elapsedMs) {
        try {
            registry.counter(METRIC_TIER_HIT, TAG_TIER, tier, TAG_HIT, String.valueOf(hit)).increment();
            registry.timer(METRIC_TIER_LATENCY, TAG_TIER, tier).record(elapsedMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[IntentMetrics] Micrometer 记录失败（非关键）", e);
        }
    }

    /**
     * 记录一次分类的最终到达层与耗时（原有指标，向后兼容）。
     *
     * @param tier        最终到达层
     * @param intentCount 识别到的意图数量
     * @param elapsedMs   全链路耗时（毫秒）
     */
    public void recordClassification(String tier, int intentCount, long elapsedMs) {
        try {
            registry.counter(METRIC_CLASSIFY_TOTAL,
                    TAG_TIER, tier, TAG_INTENT_COUNT, String.valueOf(intentCount)).increment();
            registry.timer(METRIC_CLASSIFY_LATENCY, TAG_TIER, tier)
                    .record(elapsedMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[IntentMetrics] classification 指标记录失败（非关键）", e);
        }
    }

    /**
     * 记录一次坐席反馈提交（Micrometer 实时指标）。
     *
     * @param type 反馈类型的小写形式，如 {@code wrong_intent} / {@code good}
     */
    public void recordFeedback(String type) {
        try {
            registry.counter(METRIC_FEEDBACK, TAG_TYPE, type).increment();
        } catch (Exception e) {
            log.debug("[IntentMetrics] feedback 指标记录失败（非关键）", e);
        }
    }

    /**
     * 异步写入单次分类的分层明细，供 Admin API 聚合。
     *
     * <p>使用 {@code observabilityExecutor}（DiscardPolicy），写失败不影响主流程。
     */
    @Async("observabilityExecutor")
    public void persistDetail(IntentTierStatEntity detail) {
        try {
            tierStatMapper.insert(detail);
        } catch (Exception e) {
            log.warn("[IntentTierStat] 明细写入失败 domain={}", detail.getDomainCode(), e);
        }
    }
}
