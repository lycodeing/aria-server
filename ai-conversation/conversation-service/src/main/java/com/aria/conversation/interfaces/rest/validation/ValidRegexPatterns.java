package com.aria.conversation.interfaces.rest.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/**
 * 验证 JSON 字符串数组中每条正则表达式的合法性：
 * 1. 长度 ≤ 200 字符
 * 2. 必须是合法的 Java Pattern 语法
 * 3. 禁止嵌套量词（如 {@code (a+)+}），防止 ReDoS 攻击
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RegexPatternsValidator.class)
@Documented
public @interface ValidRegexPatterns {
    String message() default "正则表达式不合法：每条长度不超过200字符，语法须合法，禁止嵌套量词（如 (a+)+）";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
