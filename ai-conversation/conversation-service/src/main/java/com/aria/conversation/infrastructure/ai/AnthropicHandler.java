package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Anthropic Claude 协议处理器。
 *
 * <p>端点：{@code POST /v1/messages}
 * <p>认证：{@code x-api-key} + {@code anthropic-version: 2023-06-01}
 * <p>流式 delta 路径：event=content_block_delta → {@code delta.text}
 *
 * <p>WebClient 按 baseUrl 缓存，避免每次请求重建 Netty 连接池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicHandler implements AiProtocolHandler {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** Spring Boot 自动配置的 ObjectMapper，继承全局配置 */
    private final ObjectMapper objectMapper;

    /** 按 baseUrl 缓存 WebClient，每个端点只创建一个连接池 */
    private final ConcurrentHashMap<String, WebClient> clientCache = new ConcurrentHashMap<>();

    @Override
    public String protocol() {
        return "ANTHROPIC";
    }

    @Override
    public Flux<String> streamChat(AiModelConfig config, List<ChatMessage> messages, String systemPrompt) {
        Map<String, Object> body = buildBody(config, messages, systemPrompt, true);
        return buildClient(config).post()
                .uri("/v1/messages")
                .header("x-api-key", config.apiKey())
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(config.timeoutSec()))
                .filter(line -> line != null && !line.isBlank());
    }

    @Override
    public String chat(AiModelConfig config, List<ChatMessage> messages, String systemPrompt) {
        Map<String, Object> body = buildBody(config, messages, systemPrompt, false);
        String response = buildClient(config).post()
                .uri("/v1/messages")
                .header("x-api-key", config.apiKey())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.timeoutSec()))
                .block();
        try {
            return objectMapper.readTree(response)
                    .path("content").path(0).path("text").asText();
        } catch (Exception e) {
            throw new RuntimeException("解析 Anthropic 响应失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String extractDeltaContent(String chunkJson) {
        try {
            var root = objectMapper.readTree(chunkJson);
            if ("content_block_delta".equals(root.path("type").asText())) {
                return root.path("delta").path("text").asText("");
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> buildBody(AiModelConfig config, List<ChatMessage> messages,
                                           String systemPrompt, boolean stream) {
        // Anthropic messages 不含 system role，system 单独传
        List<Map<String, String>> msgs = messages.stream()
                .filter(m -> !"system".equals(m.role()))
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .collect(Collectors.toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",      config.modelName());
        body.put("max_tokens", config.maxTokens());
        body.put("messages",   msgs);
        body.put("stream",     stream);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        return body;
    }

    /**
     * 按 baseUrl 缓存 WebClient，apiKey 在每次请求时单独传递（支持热切换）。
     */
    private WebClient buildClient(AiModelConfig config) {
        return clientCache.computeIfAbsent(config.baseUrl(), url ->
                WebClient.builder()
                        .baseUrl(url)
                        .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                        .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build());
    }
}
