package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

public interface LlmModelBuilder {
    boolean supports(String apiProtocol);
    ChatModel buildChatModel(AiModelConfig cfg);
    StreamingChatModel buildStreamingModel(AiModelConfig cfg);
}
