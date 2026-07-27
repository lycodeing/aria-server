package com.aria.conversation.domain.service;

import com.aria.conversation.domain.model.MultiIntentResult;

/**
 * 多意图识别领域服务接口。
 *
 * <p>实现在 infrastructure 层（{@link com.aria.conversation.infrastructure.ai.MultiHybridIntentService}），
 * 保持 DDD 分层。任何失败均返回 {@link MultiIntentResult#UNKNOWN}，不抛异常。
 */
public interface MultiIntentService {
    MultiIntentResult classifyMulti(String userMessage);
}
