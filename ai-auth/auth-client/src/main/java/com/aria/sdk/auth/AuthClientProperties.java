package com.aria.sdk.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * auth-client 配置属性。
 *
 * <p>YAML 键前缀：{@code aria.auth.client.*}
 *
 * <pre>
 * aria:
 *   auth:
 *     client:
 *       base-url: http://localhost:8083
 *       shared-secret: ${ARIA_INTERNAL_SECRET}
 *       connect-timeout-ms: 3000
 *       read-timeout-ms: 5000
 *       max-retries: 0
 * </pre>
 *
 * <p>{@link #sharedSecret} 无默认值且标注 {@link NotBlank}，缺失时 Spring 会在
 * 装配阶段抛 {@code BindException}，实现启动期 fail-fast，避免"占位符默认值 → 运行时 403"。
 *
 * @author lycodeing
 * @since 2026-07
 */
@Validated
@ConfigurationProperties(prefix = "aria.auth.client")
public class AuthClientProperties {

    /** auth-service 内网访问基础地址，例如 http://auth-service:8083。 */
    @NotBlank
    private String baseUrl = "http://localhost:8083";

    /** 与 auth-service 的 {@code aria.internal.secret} 一致的共享密钥（必填）。 */
    @NotBlank
    private String sharedSecret;

    /** 连接超时，毫秒。 */
    @Min(100)
    private Integer connectTimeoutMs = 3000;

    /** 读超时，毫秒。 */
    @Min(100)
    private Integer readTimeoutMs = 5000;

    /**
     * 最大重试次数。
     *
     * <p>默认 0：AI 模型配置读取属高频内部同步调用，上层已有 5 分钟 Redis 缓存兜底，
     * 短暂 5xx 由缓存吸收即可；重试放大延迟得不偿失（详见 {@code RetryInterceptor} 退避策略）。
     */
    @Min(0)
    private Integer maxRetries = 0;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getSharedSecret() { return sharedSecret; }
    public void setSharedSecret(String sharedSecret) { this.sharedSecret = sharedSecret; }

    public Integer getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(Integer connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public Integer getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(Integer readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
}
