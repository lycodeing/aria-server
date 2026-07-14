package com.aria.conversation.interfaces.rest.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@link ValidRegexPatterns} 的实现。
 * 对 JSON 数组字符串中的每条正则做三重校验：
 * 1. 长度 ≤ 200 字符
 * 2. 编译合法（Pattern.compile 不抛异常）
 * 3. 无嵌套量词（防 ReDoS）
 */
public class RegexPatternsValidator implements ConstraintValidator<ValidRegexPatterns, String> {

    private static final int MAX_PATTERN_LENGTH = 200;
    /** 检测嵌套量词：如 (a+)+  (x*)*  ([a-z]+)+ 等 */
    private static final Pattern NESTED_QUANTIFIER =
            Pattern.compile("\\([^()]*[+*][^()]*\\)[+*?]");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank() || value.equals("[]")) {
            return true;  // 空值合法
        }
        List<String> patterns;
        try {
            patterns = objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception e) {
            addMessage(context, "patterns 不是合法的 JSON 数组");
            return false;
        }
        for (String p : patterns) {
            if (p == null || p.isBlank()) continue;
            if (p.length() > MAX_PATTERN_LENGTH) {
                addMessage(context, "正则表达式长度不能超过 " + MAX_PATTERN_LENGTH + " 字符：" + truncate(p));
                return false;
            }
            if (NESTED_QUANTIFIER.matcher(p).find()) {
                addMessage(context, "正则表达式包含嵌套量词（ReDoS 风险），请简化：" + truncate(p));
                return false;
            }
            try {
                Pattern.compile(p);
            } catch (PatternSyntaxException e) {
                addMessage(context, "正则表达式语法错误：" + truncate(p));
                return false;
            }
        }
        return true;
    }

    private void addMessage(ConstraintValidatorContext ctx, String msg) {
        ctx.disableDefaultConstraintViolation();
        ctx.buildConstraintViolationWithTemplate(msg).addConstraintViolation();
    }

    private String truncate(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
