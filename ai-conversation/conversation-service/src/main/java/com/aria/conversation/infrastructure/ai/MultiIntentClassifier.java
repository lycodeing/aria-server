package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;

import java.util.List;

/**
 * 多意图 LLM 分类器内部接口（infrastructure 层）。
 *
 * <p>使 {@link MultiHybridIntentService} 依赖抽象而非具体实现（DIP），
 * 便于替换实现（如切换模型提供商）和独立单测（Mock）。
 * 接口定义在 infrastructure 层而非 domain 层，因为"LLM 调用"是基础设施关注点。
 */
public interface MultiIntentClassifier {

    /**
     * 对用户消息进行多意图分类。
     *
     * @param userMessage 用户消息
     * @return 分类结果列表，失败时返回含
     *         {@link com.aria.conversation.domain.model.IntentResult#UNKNOWN} 的单元素列表，不抛异常
     */
    List<IntentResult> classifyMulti(String userMessage);
}
