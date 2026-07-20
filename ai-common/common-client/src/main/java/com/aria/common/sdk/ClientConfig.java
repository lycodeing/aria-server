package com.aria.common.sdk;

import com.aria.common.sdk.auth.AuthMode;

import java.time.Duration;

/**
 * SDK 客户端配置。
 * <p>所有 SDK Client 的 Builder 接收此配置，包含连接信息、认证凭证、超时和重试策略。
 *
 * <p>鉴权模式通过 {@link AuthMode} 声明：
 * <ul>
 *   <li>{@link AuthMode#AK_SK}：默认模式，须提供 accessKey/secretKey</li>
 *   <li>{@link AuthMode#SHARED_SECRET}：内网调用，须提供 sharedSecret</li>
 *   <li>{@link AuthMode#NONE}：无鉴权</li>
 * </ul>
 */
public class ClientConfig {

    private final String baseUrl;
    private final AuthMode authMode;
    private final String accessKey;
    private final String secretKey;
    private final String sharedSecret;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration callTimeout;
    private final int maxRetries;
    private final int callbackPort;
    private final String webhookSecret;

    private ClientConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.authMode = builder.authMode;
        this.accessKey = builder.accessKey;
        this.secretKey = builder.secretKey;
        this.sharedSecret = builder.sharedSecret;
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.callTimeout = builder.callTimeout;
        this.maxRetries = builder.maxRetries;
        this.callbackPort = builder.callbackPort;
        this.webhookSecret = builder.webhookSecret;
    }

    public String getBaseUrl() { return baseUrl; }
    public AuthMode getAuthMode() { return authMode; }
    public String getAccessKey() { return accessKey; }
    public String getSecretKey() { return secretKey; }
    public String getSharedSecret() { return sharedSecret; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public Duration getCallTimeout() { return callTimeout; }
    public int getMaxRetries() { return maxRetries; }
    public int getCallbackPort() { return callbackPort; }
    public String getWebhookSecret() { return webhookSecret; }

    /**
     * 构造完整 URL（baseUrl + path）。
     */
    public String url(String path) {
        if (path == null) return baseUrl;
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private AuthMode authMode = AuthMode.AK_SK;
        private String accessKey;
        private String secretKey;
        private String sharedSecret;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        /** 整个 HTTP 调用的可选端到端时限；null 表示不额外限制。 */
        private Duration callTimeout;
        private int maxRetries = 3;
        private int callbackPort = 0;
        private String webhookSecret;

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }

        /** 显式指定鉴权模式；未调用时默认 {@link AuthMode#AK_SK}。 */
        public Builder authMode(AuthMode mode) { this.authMode = mode; return this; }

        /** 便捷方法：设置 AK/SK 并将鉴权模式切为 {@link AuthMode#AK_SK}。 */
        public Builder accessKey(String accessKey, String secretKey) {
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.authMode = AuthMode.AK_SK;
            return this;
        }

        /** 便捷方法：设置共享密钥并将鉴权模式切为 {@link AuthMode#SHARED_SECRET}。 */
        public Builder sharedSecret(String secret) {
            this.sharedSecret = secret;
            this.authMode = AuthMode.SHARED_SECRET;
            return this;
        }

        public Builder connectTimeout(Duration timeout) { this.connectTimeout = timeout; return this; }
        public Builder readTimeout(Duration timeout) { this.readTimeout = timeout; return this; }
        public Builder callTimeout(Duration timeout) { this.callTimeout = timeout; return this; }
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder callbackPort(int port) { this.callbackPort = port; return this; }
        public Builder webhookSecret(String secret) { this.webhookSecret = secret; return this; }

        public ClientConfig build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl 不能为空");
            }
            if (authMode == null) {
                throw new IllegalArgumentException("authMode 不能为空");
            }
            switch (authMode) {
                case AK_SK -> {
                    if (accessKey == null || accessKey.isBlank()
                            || secretKey == null || secretKey.isBlank()) {
                        throw new IllegalArgumentException("AK_SK 模式下 accessKey/secretKey 均不能为空");
                    }
                }
                case SHARED_SECRET -> {
                    if (sharedSecret == null || sharedSecret.isBlank()) {
                        throw new IllegalArgumentException("SHARED_SECRET 模式下 sharedSecret 不能为空");
                    }
                }
                case NONE -> { /* 无需校验 */ }
            }
            return new ClientConfig(this);
        }
    }
}
