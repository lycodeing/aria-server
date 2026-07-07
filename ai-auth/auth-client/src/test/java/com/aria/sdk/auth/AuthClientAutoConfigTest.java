package com.aria.sdk.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthClientAutoConfig} Spring Boot 自动装配集成测试。
 *
 * <p>覆盖三条路径：
 * <ol>
 *   <li>正常配置 → 装配 {@link AuthClient} Bean</li>
 *   <li>{@code aria.auth.client.enabled=false} → 不装配</li>
 *   <li>缺少 {@code shared-secret} → 因 {@code @NotBlank} 校验失败启动异常（fail-fast）</li>
 * </ol>
 *
 * @author lycodeing
 * @since 2026-07
 */
class AuthClientAutoConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthClientAutoConfig.class));

    @Test
    @DisplayName("正常配置时装配 AuthClient Bean")
    void registersAuthClientWhenPropertiesValid() {
        contextRunner
                .withPropertyValues(
                        "aria.auth.client.base-url=http://auth-service:8083",
                        "aria.auth.client.shared-secret=unit-test-secret")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AuthClient.class);
                    assertThat(ctx).hasSingleBean(AuthClientProperties.class);
                });
    }

    @Test
    @DisplayName("enabled=false 时不装配 AuthClient")
    void skipsWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues(
                        "aria.auth.client.enabled=false",
                        "aria.auth.client.shared-secret=whatever")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AuthClient.class));
    }

    @Test
    @DisplayName("缺少 shared-secret 时启动失败（fail-fast）")
    void failsFastWhenSharedSecretMissing() {
        contextRunner
                .withPropertyValues("aria.auth.client.base-url=http://auth-service:8083")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    // BindException 触发链路：ConfigurationProperties 校验失败
                    Throwable cause = ctx.getStartupFailure();
                    assertThat(cause).isNotNull();
                    // 沿异常链找 BindValidationException 或字符串包含 shared-secret 的证据
                    assertThat(hasBindValidation(cause) || cause.getMessage().contains("shared-secret")
                            || cause.getMessage().contains("sharedSecret"))
                            .as("失败原因应指向 shared-secret 校验")
                            .isTrue();
                });
    }

    private static boolean hasBindValidation(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof BindValidationException) {
                return true;
            }
        }
        return false;
    }
}
