package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议处理器。
 * 适用：OpenAI / 天翼云 / DeepSeek / 通义千问 / Moonshot 等 /v1/chat/completions 供应商。
 *
 * <p>流式 delta 路径：{@code choices[0].delta.content}
 * <p>认证：{@code Authorization: Bearer {apiKey}}
 */
@Slf4j
public class OpenAiCompatibleHandler implements AiProtocolHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String protocol() {
        return "OPENAI_COMPATIBLE";
    }

    @Override
    public Flux<String> streamChat(AiModelConfig config, List<ChatMessage> messages, String systemPrompt) {
        List<ChatMessage> full = buildMessages(messages, systemPrompt);
        Map<String, Object> body = Map.of(
                "model",       config.modelName(),
                "messages",    full,
                "stream",      true,
                "temperature", config.temperature(),
                "max_tokens",  config.maxTokens()
        );
        return buildClient(config).post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(config.timeoutSec()))
                .filter(line -> line != null && !line.isBlank())
                .map(line -> line.startsWith("data: ") ? line.substring(6) : line)
                .filter(data -> !data.equals("[DONE]"));
    }

    @Override
    public String chat(AiModelConfig config, List<ChatMessage> messages, String systemPrompt) {
        List<ChatMessage> full = buildMessages(messages, systemPrompt);
        Map<String, Object> body = Map.of(
                "model",       config.modelName(),
                "messages",    full,
                "stream",      false,
                "temperature", config.temperature(),
                "max_tokens",  config.maxTokens()
        );
        String response = buildClient(config).post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.timeoutSec()))
                .block();
        try {
            return objectMapper.readTree(response)
                    .path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("解析 OpenAI 兼容响应失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String extractDeltaContent(String chunkJson) {
        try {
            return objectMapper.readTree(chunkJson)
                    .path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private WebClient buildClient(AiModelConfig config) {
        return WebClient.builder()
                .baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    private List<ChatMessage> buildMessages(List<ChatMessage> messages, String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) return messages;
        List<ChatMessage> full = new ArrayList<>(messages.size() + 1);
        full.add(ChatMessage.system(systemPrompt));
        full.addAll(messages);
        return full;
    }
}
