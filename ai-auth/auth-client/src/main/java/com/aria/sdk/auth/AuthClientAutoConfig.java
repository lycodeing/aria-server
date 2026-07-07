package com.aria.sdk.auth;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * {@link AuthClient} Spring Boot 自动装配。
 *
 * <p>依赖 {@link AuthClientProperties}，条件如下：
 * <ul>
 *   <li>类路径存在 {@link AuthClient}</li>
 *   <li>{@code aria.auth.client.enabled} 未设置或为 {@code true}</li>
 *   <li>容器中不存在同类型 Bean（允许业务侧自定义覆盖）</li>
 * </ul>
 *
 * <p>{@code shared-secret} 未配置时 {@link AuthClientProperties} 通过 {@code @NotBlank}
 * 在 Bean 绑定阶段抛 {@code BindException}，实现启动期 fail-fast。
 *
 * @author lycodeing
 * @since 2026-07
 */
@AutoConfiguration
@ConditionalOnClass(AuthClient.class)
@ConditionalOnProperty(prefix = "aria.auth.client", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuthClientProperties.class)
public class AuthClientAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public AuthClient authClient(AuthClientProperties props) {
        return AuthClient.builder()
                .baseUrl(props.getBaseUrl())
                .sharedSecret(props.getSharedSecret())
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .maxRetries(props.getMaxRetries())
                .build();
    }
}
