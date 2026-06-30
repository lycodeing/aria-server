package com.aidevplatform.conversation.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 天翼云 AI API 客户端。
 * 封装 WebClient 调用 https://wishub-x6.ctyun.cn/v1/chat/completions。
 * 支持流式（stream=true）和非流式两种模式。
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
     * 流式对话：返回 Flux<String>，每个元素为 SSE data 行（原始 JSON 字符串）。
     * 前端通过 EventSource 消费。
     *
     * @param messages     对话消息列表，格式：[{"role":"user","content":"..."}]
     * @param systemPrompt 系统提示词（可为 null）
     */
    public Flux<String> streamChat(List<Map<String, String>> messages, String systemPrompt) {
        List<Map<String, String>> fullMessages = buildMessages(messages, systemPrompt);
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", fullMessages,
                "stream", true,
                "temperature", 0.7,
                "max_tokens", 2048
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .filter(line -> line != null && !line.isBlank())
                .map(line -> {
                    // SSE 格式：去除 "data: " 前缀
                    if (line.startsWith("data: ")) {
                        return line.substring(6);
                    }
                    return line;
                })
                .filter(data -> !data.equals("[DONE]"));
    }

    /**
     * 非流式对话：同步返回完整回复内容。
     */
    public String chat(List<Map<String, String>> messages, String systemPrompt) {
        List<Map<String, String>> fullMessages = buildMessages(messages, systemPrompt);
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", fullMessages,
                "stream", false,
                "temperature", 0.7,
                "max_tokens", 2048
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
     */
    public String extractDeltaContent(String chunkJson) {
        try {
            var root = objectMapper.readTree(chunkJson);
            return root.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private List<Map<String, String>> buildMessages(
            List<Map<String, String>> messages, String systemPrompt) {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            var list = new java.util.ArrayList<Map<String, String>>();
            list.add(Map.of("role", "system", "content", systemPrompt));
            list.addAll(messages);
            return list;
        }
        return messages;
    }
}
