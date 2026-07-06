package com.aria.conversation.infrastructure.dit.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 无认证策略（默认）。
 *
 * <p>不添加任何认证头，适用于公开 API 或内部无鉴权服务。
 */
@Component
public class NoAuthStrategy implements HttpAuthStrategy {

    @Override
    public String authType() {
        return "NONE";
    }

    @Override
    public void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper) {
        // 无认证，不添加任何请求头
    }
}
