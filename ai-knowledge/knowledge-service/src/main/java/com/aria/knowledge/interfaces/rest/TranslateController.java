package com.aria.knowledge.interfaces.rest;

import com.aria.common.web.response.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 文本翻译接口（调用天翼云 AI，用于检索测试结果的快速翻译）。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge/translate")
@Tag(name = "翻译", description = "调用 AI 将英文内容翻译为中文")
public class TranslateController {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ctyun.ai.base-url:https://wishub-x6.ctyun.cn/v1}")
    private String baseUrl;

    @Value("${ctyun.ai.api-key:}")
    private String apiKey;

    @Value("${ctyun.ai.model:DeepSeek-V4-Flash}")
    private String model;

    @Operation(summary = "将文本翻译为中文（调用 AI）")
    @PostMapping
    public R<String> translate(@RequestBody TranslateRequest req) {
        if (apiKey == null || apiKey.isBlank()) {
            return R.fail(500, "AI API Key 未配置，请设置环境变量 AI_CTYUN_API_KEY");
        }
        try {
            String prompt = "请将以下内容翻译成简体中文，只返回翻译结果，不要解释：\n\n" + req.getText();
            String result = callAI(prompt);
            return R.ok(result);
        } catch (Exception e) {
            log.error("翻译失败", e);
            return R.fail(500, "翻译失败：" + e.getMessage());
        }
    }

    private String callAI(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "stream", false,
            "max_tokens", 1024
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
            baseUrl + "/chat/completions", entity, Map.class);

        if (response.getBody() == null) throw new RuntimeException("AI 返回空响应");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("AI 返回无效响应");

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return String.valueOf(message.get("content"));
    }

    @Data
    public static class TranslateRequest {
        @NotBlank private String text;
    }
}
