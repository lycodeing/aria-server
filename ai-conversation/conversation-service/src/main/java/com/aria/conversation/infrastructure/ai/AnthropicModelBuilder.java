package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Anthropic Claude 协议的 LLM 模型构建器。
 *
 * <p>优先级最高（{@code @Order(1)}），当 apiProtocol 为 ANTHROPIC 时匹配。
 */
@Order(1)
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
