package com.aria.conversation.domain.model;

/**
 * 意图分类结果。
 *
 * @param intent     管道分叉枚举（驱动转人工/拒答/RAG 分支）
 * @param intentCode 原始业务意图 code（如 "query_order"），供下游业务 dispatch 使用
 * @param confidence 置信度 0.0~1.0
 */
public record IntentResult(IntentType intent, String intentCode, double confidence) {

    /** 兜底结果，分类失败时使用。confidence=0.0 表示完全不确定。 */
    public static final IntentResult UNKNOWN =
            new IntentResult(IntentType.UNKNOWN, "UNKNOWN", 0.0);

    /** 判断是否需要自动转人工（TRANSFER_REQUEST 或 COMPLAINT） */
    public boolean requiresTransfer() {
        return intent == IntentType.TRANSFER_REQUEST || intent == IntentType.COMPLAINT;
    }

    /** 判断是否可以跳过 RAG 检索 */
    public boolean skipRag() {
        return intent == IntentType.CHITCHAT || intent == IntentType.OUT_OF_SCOPE;
    }
}
