package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** Handles OpenAI and all compatible protocols (DeepSeek/Moonshot/Qianwen etc). Fallback implementation. */
@Order(2)
@Component
public class OpenAiModelBuilder implements LlmModelBuilder {

    @Override
    public boolean supports(String apiProtocol) {
        // Case-insensitive comparison — DB may store "ANTHROPIC" (uppercase) or "anthropic" (lowercase)
        return apiProtocol == null || !AiProtocol.ANTHROPIC.equalsIgnoreCase(apiProtocol);
    }

    @Override
    public ChatModel buildChatModel(AiModelConfig cfg) {
        return OpenAiChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxCompletionTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }

    @Override
    public StreamingChatModel buildStreamingModel(AiModelConfig cfg) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxCompletionTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }
}
