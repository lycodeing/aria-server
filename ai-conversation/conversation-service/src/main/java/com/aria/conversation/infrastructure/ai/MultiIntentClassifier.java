package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;

import java.util.List;

/**
 * 多意图 LLM 分类器内部接口（infrastructure 层）。
 *
 * <p>使 {@link MultiHybridIntentService} 依赖抽象而非具体实现（DIP），
 * 便于替换实现和独立单测。
 */
public interface MultiIntentClassifier {

    /**
     * 基于 {@code __system__} 域意图做多意图分类（通用路径）。
     */
    List<IntentResult> classifyMulti(String userMessage);

    /**
     * 基于调用方传入的意图列表做多意图分类（域感知路径）。
     *
     * <p>允许上层将 {@code __system__} 域意图 + 活跃域意图合并后传入，
     * LLM Prompt 中包含完整的业务意图上下文。
     *
     * @param intents 合并后的意图列表（去重，不为空）
     */
    List<IntentResult> classifyMulti(String userMessage, List<IntentConfig> intents);
}
