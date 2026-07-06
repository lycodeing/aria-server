package com.aria.conversation.domain.service;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.model.SlotDefinition;

import java.util.List;
import java.util.Map;

/**
 * 槽位提取领域服务接口。
 *
 * <p>接口定义在领域层，LangChain4j 实现在 infrastructure 层，保持 DDD 依赖方向。
 * 所有参数均为领域层类型（{@link ConversationMessage}、{@link SlotDefinition}），
 * 不泄漏任何 infrastructure 类型。
 *
 * <p>失败时返回空 Map，不抛出异常。
 */
public interface SlotService {

    /**
     * 从用户消息和对话历史中提取意图所需的槽位值。
     *
     * @param userMessage   用户当前消息
     * @param recentHistory 最近对话历史（供 LLM 上下文参考）
     * @param slots         需要提取的槽位定义列表
     * @return 槽位名到提取值的映射；无法提取时返回空 Map
     */
    Map<String, Object> extract(String userMessage,
                                List<ConversationMessage> recentHistory,
                                List<SlotDefinition> slots);
}
