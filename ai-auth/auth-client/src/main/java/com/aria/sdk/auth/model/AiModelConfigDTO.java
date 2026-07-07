package com.aria.sdk.auth.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * auth-service 内部接口返回的 AI 模型配置。
 *
 * <p>字段与 {@code /internal/ai-models/**} 响应体一一对应；使用装箱类型允许字段缺失，
 * 由上层根据 {@link ModelScope} 决定默认值。
 *
 * <p>⚠️ {@link #apiKey} 为服务端解密后的明文，严禁写入日志或转发到不受信端点。
 * 覆盖 {@link #toString()} 屏蔽 {@code apiKey}，即使调用方误将 DTO 直接打印也不会泄漏密钥。
 *
 * @author lycodeing
 * @since 2026-07
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiModelConfigDTO(
        Long   id,
        String name,
        String provider,
        String apiProtocol,
        String baseUrl,
        String apiKey,
        String modelName,
        Double temperature,
        Integer maxTokens,
        Integer timeoutSec
) {
    /** 屏蔽 apiKey 字段，防止误打印导致密钥泄漏。 */
    @Override
    public String toString() {
        return "AiModelConfigDTO{"
                + "id=" + id
                + ", name='" + name + '\''
                + ", provider='" + provider + '\''
                + ", apiProtocol='" + apiProtocol + '\''
                + ", baseUrl='" + baseUrl + '\''
                + ", apiKey='***'"
                + ", modelName='" + modelName + '\''
                + ", temperature=" + temperature
                + ", maxTokens=" + maxTokens
                + ", timeoutSec=" + timeoutSec
                + '}';
    }
}
