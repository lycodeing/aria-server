package com.aria.sdk.auth.token;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Token 校验结果。
 *
 * <p>{@link #valid} 为 {@code true} 时 {@link #userId} 保证非空。
 *
 * @author lycodeing
 * @since 2026-07
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenVerifyResult(Boolean valid, String userId) {
}
