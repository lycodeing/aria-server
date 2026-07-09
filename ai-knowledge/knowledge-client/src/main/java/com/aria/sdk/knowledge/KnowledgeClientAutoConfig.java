package com.aria.sdk.knowledge;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * {@link KnowledgeClient} Spring Boot 自动装配。
 *
 * <p>依赖 {@link KnowledgeClientProperties}，条件如下：
 * <ul>
 *   <li>类路径存在 {@link KnowledgeClient}</li>
 *   <li>{@code knowledge.client.enabled} 未设置或为 {@code true}</li>
 *   <li>容器中不存在同类型 Bean（允许业务侧自定义覆盖）</li>
 * </ul>
 *
 * <p>{@code access-key} / {@code secret-key} 未配置时 {@link KnowledgeClientProperties}
 * 通过 {@code @NotBlank} 在 Bean 绑定阶段抛 {@code BindException}，实现启动期 fail-fast。
 *
 * <p>若同时配置了 {@code shared-secret}，则优先使用 SHARED_SECRET 模式；
 * 否则使用 AK/SK 签名模式。
 *
 * @author lycodeing
 * @since 2026-07
 */
@AutoConfiguration
@ConditionalOnClass(KnowledgeClient.class)
@ConditionalOnProperty(prefix = "knowledge.client", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KnowledgeClientProperties.class)
public class KnowledgeClientAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeClient knowledgeClient(KnowledgeClientProperties props) {
        KnowledgeClient.Builder builder = KnowledgeClient.builder()
                .baseUrl(props.getBaseUrl())
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(props.getReadTimeoutMs()))
                .maxRetries(props.getMaxRetries());

        // shared-secret 优先，否则使用 AK/SK
        if (props.getSharedSecret() != null && !props.getSharedSecret().isBlank()) {
            builder.sharedSecret(props.getSharedSecret());
        } else {
            builder.accessKey(props.getAccessKey(), props.getSecretKey());
        }

        return builder.build();
    }
}