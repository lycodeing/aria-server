package com.aria.knowledge.interfaces.rest;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.common.web.response.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 文本翻译接口（通过 AiModelConfigProvider 调用当前激活 AI 模型，用于检索测试结果的快速翻译）。
 *
 * <p>仅在 {@code knowledge.translate.enabled=true} 时启用，默认关闭，
 * 防止作为调试工具意外暴露在生产环境。
 *
 * <p>复用 {@link AiModelConfigProvider} 而非直接调用天翼云 API，确保：
 * <ul>
 *   <li>API Key 统一走加密管理，不在 yml 明文存储</li>
 *   <li>WebClient 设置超时，不阻塞 Tomcat 线程池</li>
 *   <li>切换 AI 供应商时此接口自动跟随</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge/translate")
@RequiredArgsConstructor
@Tag(name = "翻译", description = "调用 AI 将英文内容翻译为中文")
@ConditionalOnProperty(name = "knowledge.translate.enabled", havingValue = "true", matchIfMissing = false)
public class TranslateController {

    private final AiModelConfigProvider configProvider;
    private final WebClient.Builder webClientBuilder;

    @Operation(summary = "将文本翻译为中文")
    @PostMapping
    public R<String> translate(@RequestBody @Valid TranslateRequest req) {
        AiModelConfig config = configProvider.getActive();
        try {
            String prompt = "请将以下内容翻译成简体中文，只返回翻译结果，不要解释：\n\n" + req.getText();
            Map<String, Object> body = Map.of(
                "model",      config.modelName(),
                "messages",   List.of(Map.of("role", "user", "content", prompt)),
                "stream",     false,
                "max_tokens", 1024
            );
            String response = webClientBuilder.clone()
                    .baseUrl(config.baseUrl())
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                    .build()
                    .post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(config.timeoutSec()))
                    .block();
            // 解析 choices[0].message.content
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            String content = om.readTree(response)
                    .path("choices").path(0).path("message").path("content").asText();
            return R.ok(content);
        } catch (Exception e) {
            log.error("[Translate] 翻译失败", e);
            return R.fail(500, "翻译失败：" + e.getMessage());
        }
    }

    @Data
    public static class TranslateRequest {
        @NotBlank(message = "翻译内容不能为空")
        private String text;
    }
}
