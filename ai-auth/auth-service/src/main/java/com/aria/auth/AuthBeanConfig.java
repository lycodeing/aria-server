package com.aria.auth;

import com.aria.auth.domain.service.LoginAttemptPolicy;
import com.aria.auth.domain.service.PasswordPolicyChecker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 认证服务 Bean 配置。
 */
@Configuration
public class AuthBeanConfig {

    @Bean
    public LoginAttemptPolicy loginAttemptPolicy(
            @Value("${adp.auth.login.max-fail:5}") int maxFail,
            @Value("${adp.auth.login.lock-minutes:30}") long lockMinutes) {
        if (maxFail <= 0) {
            throw new IllegalArgumentException("adp.auth.login.max-fail 必须 > 0，当前值：" + maxFail);
        }
        if (lockMinutes <= 0) {
            throw new IllegalArgumentException("adp.auth.login.lock-minutes 必须 > 0，当前值：" + lockMinutes);
        }
        return new LoginAttemptPolicy(maxFail, lockMinutes);
    }

    @Bean
    public PasswordPolicyChecker passwordPolicyChecker(
            @Value("${adp.auth.password.min-length:8}") int minLength,
            @Value("${adp.auth.password.require-upper:true}") boolean requireUpper,
            @Value("${adp.auth.password.require-digit:true}") boolean requireDigit,
            @Value("${adp.auth.password.require-special:true}") boolean requireSpecial) {
        if (minLength < 6) {
            throw new IllegalArgumentException("adp.auth.password.min-length 最小值为 6，当前值：" + minLength);
        }
        return new PasswordPolicyChecker(minLength, requireUpper, requireDigit, requireSpecial);
    }
}
