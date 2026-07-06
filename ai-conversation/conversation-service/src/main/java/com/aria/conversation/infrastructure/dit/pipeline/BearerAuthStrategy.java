package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.common.core.util.EncryptUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Bearer Token 认证策略。
 *
 * <p>从 authConfig JSON 中读取 {@code token_encrypted} 字段，解密后
 * 设置为 HTTP {@code Authorization: Bearer <token>} 请求头。
 * 字段值支持 {@code PLAINTEXT:{raw}} 和 {@code AES:{base64}} 两种格式，
 * 与 {@code AiModelConfigService.decryptApiKey()} 保持一致。
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
            String encToken = mapper.readTree(authConfig).path("token_encrypted").asText("");
            if (!encToken.isBlank()) {
                headers.setBearerAuth(decryptField(encToken));
            }
        } catch (Exception e) {
            log.warn("[DIT] BEARER 认证头解析失败", e);
        }
    }

    /**
     * 解密字段值，支持 PLAINTEXT: 和 AES: 两种前缀。
     * 无前缀时按明文处理（向后兼容旧数据）。
     */
    private static String decryptField(String value) {
        if (value.startsWith("PLAINTEXT:")) {
            return value.substring(10);
        }
        if (value.startsWith("AES:")) {
            return EncryptUtils.decrypt(value.substring(4));
        }
        // 兼容旧版无前缀明文数据
        return value;
    }
}
