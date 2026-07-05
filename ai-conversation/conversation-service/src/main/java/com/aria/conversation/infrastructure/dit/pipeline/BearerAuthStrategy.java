package com.aria.conversation.infrastructure.dit.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BearerAuthStrategy implements HttpAuthStrategy {
    @Override public String authType() { return "BEARER"; }
    @Override
    public void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper) {
        if (authConfig == null || authConfig.isBlank()) return;
        try {
            String token = mapper.readTree(authConfig).path("token_encrypted").asText("");
            if (!token.isBlank()) headers.setBearerAuth(token);
        } catch (Exception e) {
            log.warn("[DIT] BEARER 认证头解析失败", e);
        }
    }
}
