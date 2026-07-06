package com.aria.conversation.infrastructure.dit.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Bearer Token 认证策略。
 *
 * <p>从 authConfig JSON 中读取 {@code token_encrypted} 字段，
 * 设置为 HTTP {@code Authorization: Bearer <token>} 请求头。
 * authConfig 为空时跳过，不抛异常。
 */
@Slf4j
@Component
public class BearerAuthStrategy implements HttpAuthStrategy {

    @Override
    public String authType() {
        return "BEARER";
    }

    @Override
    public void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper) {
        if (authConfig == null || authConfig.isBlank()) {
            return;
        }
        try {
            String token = mapper.readTree(authConfig).path("token_encrypted").asText("");
            if (!token.isBlank()) {
                headers.setBearerAuth(token);
            }
        } catch (Exception e) {
            log.warn("[DIT] BEARER 认证头解析失败", e);
        }
    }
}
