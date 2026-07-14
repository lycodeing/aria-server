package com.aria.conversation.interfaces.rest.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("RegexPatternsValidator")
class RegexPatternsValidatorTest {

    @Mock private ConstraintValidatorContext ctx;
    @Mock private ConstraintValidatorContext.ConstraintViolationBuilder builder;
    private RegexPatternsValidator validator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new RegexPatternsValidator();
        when(ctx.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
        when(builder.addConstraintViolation()).thenReturn(ctx);
    }

    @Test
    @DisplayName("null / 空 / 空数组 → 合法")
    void nullAndEmptyAreValid() {
        assertThat(validator.isValid(null, ctx)).isTrue();
        assertThat(validator.isValid("", ctx)).isTrue();
        assertThat(validator.isValid("[]", ctx)).isTrue();
    }

    @Test
    @DisplayName("合法 pattern → 通过")
    void validPattern_passes() {
        assertThat(validator.isValid("[\"^我要.*转.*人工\",\".*退款.*\"]", ctx)).isTrue();
    }

    @Test
    @DisplayName("超过 200 字符 → 拒绝")
    void tooLongPattern_rejected() {
        String longPattern = "[\"" + "a".repeat(201) + "\"]";
        assertThat(validator.isValid(longPattern, ctx)).isFalse();
    }

    @Test
    @DisplayName("嵌套量词 (a+)+ → 拒绝")
    void nestedQuantifier_rejected() {
        assertThat(validator.isValid("[\"(a+)+\"]", ctx)).isFalse();
    }

    @Test
    @DisplayName("嵌套量词 (x*)* → 拒绝")
    void nestedQuantifierStar_rejected() {
        assertThat(validator.isValid("[\"(x*)*\"]", ctx)).isFalse();
    }

    @Test
    @DisplayName("语法错误的 pattern → 拒绝")
    void syntaxErrorPattern_rejected() {
        assertThat(validator.isValid("[\"[unclosed\"]", ctx)).isFalse();
    }

    @Test
    @DisplayName("非 JSON 数组字符串 → 拒绝")
    void nonJsonArray_rejected() {
        assertThat(validator.isValid("not-json", ctx)).isFalse();
    }
}
