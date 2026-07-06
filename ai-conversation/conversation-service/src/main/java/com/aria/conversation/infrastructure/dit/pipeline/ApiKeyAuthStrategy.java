package com.aria.conversation.infrastructure.dit.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * API Key 认证策略。
 *
 * <p>从 authConfig JSON 中读取 {@code header}（默认 {@code X-API-Key}）和
 * {@code value_encrypted} 字段，将 API Key 写入指定请求头。
 * authConfig 为空时跳过，不抛异常。
 */
@Slf4j
@Component
public class ApiKeyAuthStrategy implements HttpAuthStrategy {

    @Override
    public String authType() {
        return "API_KEY";
    }

    @Override
    public void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper) {
        if (authConfig == null || authConfig.isBlank()) {
            return;
        }
        try {
            var auth = mapper.readTree(authConfig);
            String headerName = auth.path("header").asText("X-API-Key");
            String value = auth.path("value_encrypted").asText("");
            if (!value.isBlank()) {
                headers.set(headerName, value);
            }
        } catch (Exception e) {
            log.warn("[DIT] API_KEY 认证头解析失败", e);
        }
    }
}
