package com.aria.conversation.infrastructure.observability;

import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * LLM Token 成本记录器。
 *
 * <p>拦截 LLM 调用返回的 {@link TokenUsage}，异步落库到 {@code cs_conversation.cs_llm_cost_log}，
 * 并打 Micrometer 计数器，供管理台成本报表与 /actuator 实时观测使用。
 *
 * <p>写入使用独立的 {@code observabilityExecutor}（DiscardPolicy），主链路不受影响；
 * {@code usage} 为 null（部分流式 endpoint 默认不返回用量）时静默跳过。
 */
@Slf4j
@Component
public class LlmCostLogger {

    private static final String METRIC_INPUT  = "llm.token.input.total";
    private static final String METRIC_OUTPUT = "llm.token.output.total";
    private static final String TAG_MODEL     = "model";

    private final LlmCostLogMapper mapper;
    private final MeterRegistry registry;

    public LlmCostLogger(LlmCostLogMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
    }

    /**
     * 异步记录一次 LLM 调用的 Token 消耗。
     *
     * @param sessionId 会话 ID，可为 null
     * @param modelName 实际模型名
     * @param callType  调用类型：CHAT / INTENT_CLASSIFY
     * @param usage     LangChain4j TokenUsage，可为 null（则跳过）
     * @param latencyMs 调用耗时（毫秒）
     */
    @Async("observabilityExecutor")
    public void logAsync(String sessionId, String modelName, String callType,
                         TokenUsage usage, long latencyMs) {
        if (usage == null) {
            return;
        }
        try {
            Integer input  = usage.inputTokenCount();
            Integer output = usage.outputTokenCount();
            Integer total  = usage.totalTokenCount();

            LlmCostLogEntity entity = LlmCostLogEntity.builder()
                    .sessionId(sessionId)
                    .modelName(modelName)
                    .callType(callType)
                    .inputTokens(input)
                    .outputTokens(output)
                    .totalTokens(total)
                    .latencyMs((int) latencyMs)
                    .build();
            mapper.insert(entity);

            String model = modelName != null ? modelName : "unknown";
            if (input != null) {
                registry.counter(METRIC_INPUT, TAG_MODEL, model).increment(input);
            }
            if (output != null) {
                registry.counter(METRIC_OUTPUT, TAG_MODEL, model).increment(output);
            }
        } catch (Exception e) {
            log.warn("[LlmCost] Token 成本记录写入失败 session={} model={}", sessionId, modelName, e);
        }
    }
}
