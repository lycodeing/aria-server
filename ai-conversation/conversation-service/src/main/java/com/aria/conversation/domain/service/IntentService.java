package com.aria.conversation.domain.service;

import com.aria.conversation.domain.model.IntentResult;

/**
 * 意图识别领域服务接口。
 * 接口定义在领域层，LangChain4j 实现在 infrastructure 层，保持 DDD 分层。
 * 失败时返回 {@link IntentResult#UNKNOWN}，不抛出异常。
 */
public interface IntentService {
    IntentResult classify(String userMessage);
}
