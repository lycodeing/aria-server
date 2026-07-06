package com.aria.auth.interfaces.rest.vo;

/**
 * Token 刷新结果 VO。
 */
public record TokenRefreshVO(String tokenValue, long expiresIn) {
}
