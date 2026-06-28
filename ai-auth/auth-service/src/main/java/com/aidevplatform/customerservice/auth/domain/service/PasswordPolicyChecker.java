package com.aidevplatform.customerservice.auth.domain.service;

import com.aidevplatform.common.core.exception.BusinessException;

/**
 * 密码策略校验器（领域服务）。
 * <p>校验密码复杂度：最小长度、大写字母、数字、特殊字符。
 */
public class PasswordPolicyChecker {

    private final int minLength;
    private final boolean requireUpper;
    private final boolean requireDigit;
    private final boolean requireSpecial;

    public PasswordPolicyChecker(int minLength, boolean requireUpper, boolean requireDigit, boolean requireSpecial) {
        this.minLength = minLength;
        this.requireUpper = requireUpper;
        this.requireDigit = requireDigit;
        this.requireSpecial = requireSpecial;
    }

    /**
     * 校验明文密码是否满足策略。
     *
     * @throws BusinessException 不满足时抛出，HTTP 400
     */
    public void check(String plain) {
        if (plain == null || plain.length() < minLength) {
            throw BusinessException.of(400, "密码长度不足（最少 " + minLength + " 位）");
        }
        if (requireUpper && !plain.matches(".*[A-Z].*")) {
            throw BusinessException.of(400, "密码需包含大写字母");
        }
        if (requireDigit && !plain.matches(".*\\d.*")) {
            throw BusinessException.of(400, "密码需包含数字");
        }
        if (requireSpecial && !plain.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            throw BusinessException.of(400, "密码需包含特殊字符");
        }
    }
}
