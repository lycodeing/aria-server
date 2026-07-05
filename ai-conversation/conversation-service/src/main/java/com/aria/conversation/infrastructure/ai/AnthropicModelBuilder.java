package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** Handles Anthropic Claude protocol. */
@Component
public class AnthropicModelBuilder implements LlmModelBuilder {

    @Override
    public boolean supports(String apiProtocol) {
        return AiProtocol.ANTHROPIC.equalsIgnoreCase(apiProtocol);
    }

    @Override
    public ChatModel buildChatModel(AiModelConfig cfg) {
        return AnthropicChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }

    @Override
    public StreamingChatModel buildStreamingModel(AiModelConfig cfg) {
        return AnthropicStreamingChatModel.builder()
                .baseUrl(cfg.baseUrl()).apiKey(cfg.apiKey())
                .modelName(cfg.modelName()).maxTokens(cfg.maxTokens())
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSec()))
                .build();
    }
}
