package com.aria.conversation.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 天翼云 AI API 客户端。
 * 封装 WebClient 调用 {@code /v1/chat/completions}，支持流式和非流式两种模式。
 */
@Component
public class CtyunAiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${ai.ctyun.model:DeepSeek-V4-Flash}")
    private String model;

    @Value("${ai.ctyun.timeout-seconds:60}")
    private int timeoutSeconds;

    public CtyunAiClient(
            @Value("${ai.ctyun.base-url}") String baseUrl,
            @Value("${ai.ctyun.api-key}") String apiKey) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 流式对话：返回 Flux，每个元素为原始 SSE JSON chunk 字符串。
     * 前端通过 {@code fetch + ReadableStream} 消费，Controller 负责包装成 SSE 事件。
     *
     * @param messages     对话消息列表（不含 system，由 systemPrompt 注入）
     * @param systemPrompt 系统提示词，null 或空时不注入
     */
    public Flux<String> streamChat(List<ChatMessage> messages, String systemPrompt) {
        List<ChatMessage> fullMessages = buildMessages(messages, systemPrompt);
        Map<String, Object> requestBody = Map.of(
                "model",       model,
                "messages",    fullMessages,
                "stream",      true,
                "temperature", 0.7,
                "max_tokens",  2048
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .filter(line -> line != null && !line.isBlank())
                .map(line -> line.startsWith("data: ") ? line.substring(6) : line)
                .filter(data -> !data.equals("[DONE]"));
    }

    /**
     * 非流式对话：阻塞等待并返回完整回复文本。
     *
     * @param messages     对话消息列表
     * @param systemPrompt 系统提示词
     * @return AI 回复正文
     */
    public String chat(List<ChatMessage> messages, String systemPrompt) {
        List<ChatMessage> fullMessages = buildMessages(messages, systemPrompt);
        Map<String, Object> requestBody = Map.of(
                "model",       model,
                "messages",    fullMessages,
                "stream",      false,
                "temperature", 0.7,
                "max_tokens",  2048
        );

        String response = webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        try {
            var root = objectMapper.readTree(response);
            return root.path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("解析 AI 响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从流式 SSE chunk JSON 中提取 delta content 文本。
     * chunk 格式异常时返回空字符串，不向上抛出。
     */
    public String extractDeltaContent(String chunkJson) {
        try {
            var root = objectMapper.readTree(chunkJson);
            return root.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 若有 systemPrompt，则在消息列表头部插入 system 消息。
     */
    private List<ChatMessage> buildMessages(List<ChatMessage> messages, String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return messages;
        }
        List<ChatMessage> full = new ArrayList<>(messages.size() + 1);
        full.add(ChatMessage.system(systemPrompt));
        full.addAll(messages);
        return full;
    }
}
