package com.aria.auth.interfaces.rest.vo;

/**
 * 当前登录信息 VO（/auth/me 接口）。
 */
public record MeVO(Long userId, String tokenValue, boolean isLogin) {
}
