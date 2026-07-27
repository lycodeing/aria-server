package com.aria.conversation.domain.model;

import java.util.Comparator;
import java.util.List;

/**
 * 多意图分类结果。持有所有通过阈值的意图列表，并提供路由决策语义。
 *
 * <p>不可变值对象，线程安全。
 *
 * <p><b>注意：</b>{@code sourceTier} 字段为可观测性用途，使用字符串而非枚举，
 * 避免将基础设施层（RULE/EMBEDDING/LLM）的技术概念引入领域对象。
 *
 * @param intents      所有命中的意图，按置信度降序排列，不可为 null
 * @param sourceTier   实际命中的处理层标识（"RULE"/"EMBEDDING"/"LLM"），仅用于日志和指标
 * @param processingMs 分类耗时（毫秒），用于性能监控
 */
public record MultiIntentResult(
        List<IntentResult> intents,
        String sourceTier,
        long processingMs
) {

    /**
     * 兜底结果。
     * sourceTier 用局部字符串，domain 层不引用 infra 常量（有意设计，避免 domain→infra 依赖）。
     */
    public static final MultiIntentResult UNKNOWN =
            new MultiIntentResult(List.of(IntentResult.UNKNOWN), "RULE", 0L);

    /**
     * 主意图：按 {@link IntentPriority} 取优先级最高的意图。
     * 驱动管道分叉的"主线"路由，多意图时取最紧急的。
     */
    public IntentResult primaryIntent() {
        return intents.stream()
                .min(Comparator.comparingInt(r -> IntentPriority.of(r.intent()).getOrder()))
                .orElse(IntentResult.UNKNOWN);
    }

    /**
     * 任意一个意图需要转人工，则整体需要转人工（union 语义）。
     * 安全兜底：不因为有其他意图而忽略转人工信号。
     */
    public boolean requiresTransfer() {
        return intents.stream().anyMatch(IntentResult::requiresTransfer);
    }

    /**
     * 仅当所有意图都可跳过 RAG 时，才跳过 RAG（intersection 语义）。
     * 只要有一个意图需要 RAG，就执行 RAG。
     */
    public boolean skipRag() {
        return intents.stream().allMatch(IntentResult::skipRag);
    }

    /**
     * 判断是否所有有效意图均为 OUT_OF_SCOPE 或 UNKNOWN。
     *
     * <p>供 Application 层做"整体拒答"路由决策使用，将路由判断逻辑
     * 收拢在领域对象内，避免业务规则泄漏到 Application Service。
     *
     * @return true 表示没有任何可回答的有效意图，应返回拒答模板
     */
    public boolean isEffectivelyOutOfScope() {
        return intents.stream()
                .allMatch(r -> r.intent() == IntentType.OUT_OF_SCOPE
                            || r.intent() == IntentType.UNKNOWN);
    }

    /**
     * 是否包含某个具体的业务意图 code。
     */
    public boolean hasIntentCode(String intentCode) {
        return intents.stream().anyMatch(r -> intentCode.equalsIgnoreCase(r.intentCode()));
    }

    /**
     * 所有业务意图 code 列表，供下游 dispatch 使用。
     */
    public List<String> intentCodes() {
        return intents.stream().map(IntentResult::intentCode).toList();
    }
}
