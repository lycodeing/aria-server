package com.aria.customerservice.auth;

import com.aria.customerservice.auth.domain.service.LoginAttemptPolicy;
import com.aria.customerservice.auth.domain.service.PasswordPolicyChecker;
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
        return new LoginAttemptPolicy(maxFail, lockMinutes);
    }

    @Bean
    public PasswordPolicyChecker passwordPolicyChecker(
            @Value("${adp.auth.password.min-length:8}") int minLength,
            @Value("${adp.auth.password.require-upper:true}") boolean requireUpper,
            @Value("${adp.auth.password.require-digit:true}") boolean requireDigit,
            @Value("${adp.auth.password.require-special:true}") boolean requireSpecial) {
        return new PasswordPolicyChecker(minLength, requireUpper, requireDigit, requireSpecial);
    }
}
