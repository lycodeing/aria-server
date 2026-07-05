package com.aria.conversation.domain.model;

/**
 * 意图分类结果。
 *
 * @param intent     识别到的意图
 * @param confidence 置信度 0.0~1.0，LLM 返回字段缺失时默认 1.0
 */
public record IntentResult(IntentType intent, double confidence) {

    /** 兜底结果，分类失败时使用 */
    public static final IntentResult UNKNOWN = new IntentResult(IntentType.UNKNOWN, 1.0);

    /** 判断是否需要自动转人工（TRANSFER_REQUEST 或 COMPLAINT） */
    public boolean requiresTransfer() {
        return intent == IntentType.TRANSFER_REQUEST || intent == IntentType.COMPLAINT;
    }

    /** 判断是否可以跳过 RAG 检索 */
    public boolean skipRag() {
        return intent == IntentType.CHITCHAT || intent == IntentType.OUT_OF_SCOPE;
    }
}
