package com.aria.sdk.auth.token;

/**
 * Token 校验请求体。
 *
 * <p>对应 auth-service 端点：{@code POST /api/v1/internal/token/verify}。
 *
 * @author lycodeing
 * @since 2026-07
 */
public record TokenVerifyRequest(String token) {
}
