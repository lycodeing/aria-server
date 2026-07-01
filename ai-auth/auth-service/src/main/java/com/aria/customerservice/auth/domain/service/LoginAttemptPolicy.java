package com.aria.customerservice.auth.domain.service;

import lombok.Getter;

/**
 * 登录尝试策略（领域服务）。
 * <p>定义最大失败次数和锁定时长。
 */
@Getter
public class LoginAttemptPolicy {

    private final int maxFailCount;
    private final long lockDurationMinutes;

    public LoginAttemptPolicy(int maxFailCount, long lockDurationMinutes) {
        this.maxFailCount = maxFailCount;
        this.lockDurationMinutes = lockDurationMinutes;
    }

}
