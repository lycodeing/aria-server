package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.event.IntentConfigChangedEvent;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.aria.conversation.infrastructure.example.IntentExampleVectorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图触发案例自动积累服务。
 *
 * <p>将 Tier3 LLM 高置信度结果异步写入历史案例库（数据飞轮）。
 * 独立 {@code @Component} 是关键设计决策：{@link MultiHybridIntentService} 调用本类方法时，
 * Spring CGLIB 代理才能正确拦截 {@code @Async}。
 * 若在同一类内使用 {@code this.} 自调用，代理会被绕过，{@code @Async} 完全失效。
 *
 * <p>使用专用线程池 {@code intentAccumulateExecutor}（与原型重建线程池分离），
 * 防止高频积累任务挤压低频的配置变更重建任务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntentAccumulationService {

    private final EmbeddingService embeddingService;
    private final IntentExampleVectorRepository exampleVectorRepo;
    private final MeterRegistry meterRegistry;
    /** 发布意图配置变更事件，触发原型重建（防抖由 prototypeRebuildExecutor 的 DiscardOldestPolicy 保证） */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 异步积累高置信度意图案例。
     *
     * <p>必须通过注入的 Bean 调用，不可在 {@link MultiHybridIntentService} 内自调用，
     * 否则 {@code @Async} 不生效。
     *
     * @param llmResults  Tier3 LLM 返回的意图列表
     * @param userMessage 原始用户消息（作为案例文本入库）
     * @param cfg         路由配置（含积累开关和置信度门槛）
     */
    @Async("intentAccumulateExecutor")
    public void asyncAccumulate(List<IntentResult> llmResults,
                                 String userMessage,
                                 RoutingConfig.Intent cfg) {
        if (!cfg.isAutoAccumulateEnabled()) {
            return;
        }
        double threshold = cfg.getAutoAccumulateMinConfidence();
        for (IntentResult r : llmResults) {
            // UNKNOWN 意图不积累（LLM 幻觉/不确定，积累无意义）
            if (r.intent() == IntentType.UNKNOWN || r.confidence() < threshold) {
                continue;
            }
            try {
                float[] embedding = embeddingService.encode(userMessage);
                exampleVectorRepo.saveIfAbsent(r.intentCode(), userMessage, embedding, true);
                log.debug("[Accumulate] 积累案例 intentCode={} confidence={}",
                        r.intentCode(), String.format("%.3f", r.confidence()));
                meterRegistry.counter("intent.example.accumulate.total",
                        "intent_code", r.intentCode(), "source", "auto").increment();
            } catch (Exception e) {
                // 积累失败不影响主流程，仅记录 warn
                log.warn("[Accumulate] 积累失败 intentCode={}", r.intentCode(), e);
            }
        }
    }

    /**
     * 人工确认样本积累入口。由坐席纠错反馈触发，{@code autoConfirmed=false}（人工确认，质量高于自动积累）。
     *
     * <p>与 {@link #asyncAccumulate} 的差异：
     * <ul>
     *   <li>无置信度门槛（人工确认即视为高置信）；</li>
     *   <li>不在本方法内直接调用 {@code IntentPrototypeStore.rebuild()}（单条纠错全量重建代价过高、
     *       且会清空 Caffeine 缓存导致重建期命中率抖动）。改为发布 {@link IntentConfigChangedEvent}，
     *       由 {@code prototypeRebuildExecutor}（DiscardOldestPolicy）天然防抖，坐席批量提交时合并重建。</li>
     * </ul>
     *
     * <p>必须通过注入的 Bean 调用以使 {@code @Async} 生效。
     *
     * @param intentCode  坐席确认的正确意图 code
     * @param messageText 用户原始消息
     * @param onSuccess   处理完成后的回调（用于更新反馈记录的 accumulated 标记）；可为 null
     */
    @Async("intentAccumulateExecutor")
    public void manualAccumulate(String intentCode, String messageText, Runnable onSuccess) {
        try {
            float[] embedding = embeddingService.encode(messageText);
            // saveIfAbsent 返回 void 且内部吞异常（ON CONFLICT DO NOTHING），此处无法区分"新写入/已存在"，
            // 统一按"已处理"对待：发事件触发重建 + 计数 + 回调，语义上幂等安全。
            exampleVectorRepo.saveIfAbsent(intentCode, messageText, embedding, false);

            // 发布配置变更事件，触发原型重建（防抖交给 prototypeRebuildExecutor）
            eventPublisher.publishEvent(new IntentConfigChangedEvent(intentCode));

            meterRegistry.counter("intent.example.accumulate.total",
                    "intent_code", intentCode, "source", "manual").increment();
            log.info("[ManualAccumulate] 人工样本已积累 intentCode={}", intentCode);
        } catch (Exception e) {
            log.warn("[ManualAccumulate] 积累失败 intentCode={}", intentCode, e);
        } finally {
            // 无论写入成功与否都执行回调，避免反馈记录卡在未处理状态
            if (onSuccess != null) {
                try {
                    onSuccess.run();
                } catch (Exception e) {
                    log.warn("[ManualAccumulate] onSuccess 回调失败 intentCode={}", intentCode, e);
                }
            }
        }
    }
}
