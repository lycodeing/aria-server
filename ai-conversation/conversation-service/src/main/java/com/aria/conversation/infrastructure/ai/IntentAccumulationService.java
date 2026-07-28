package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.embedding.EmbeddingService;
import com.aria.conversation.infrastructure.example.IntentExampleVectorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                        "intent_code", r.intentCode()).increment();
            } catch (Exception e) {
                // 积累失败不影响主流程，仅记录 warn
                log.warn("[Accumulate] 积累失败 intentCode={}", r.intentCode(), e);
            }
        }
    }
}
