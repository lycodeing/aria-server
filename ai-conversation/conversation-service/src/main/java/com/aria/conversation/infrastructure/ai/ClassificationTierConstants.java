package com.aria.conversation.infrastructure.ai;

/**
 * 意图分类处理层级标识常量。
 *
 * <p>供 {@link MultiHybridIntentService} 填充
 * {@link com.aria.conversation.domain.model.MultiIntentResult#sourceTier()} 字段，
 * 以及 Micrometer 指标的 tier tag 使用。
 * 不放在领域层，因为 RULE/EMBEDDING/LLM 是基础设施实现细节。
 */
public interface ClassificationTierConstants {
    String RULE      = "RULE";
    String EMBEDDING = "EMBEDDING";
    String LLM       = "LLM";
}
