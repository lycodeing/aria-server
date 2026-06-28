package com.aidevplatform.common.sdk;

import java.time.Duration;

/**
 * SDK 客户端配置。
 * <p>所有 SDK Client 的 Builder 接收此配置，包含连接信息、认证凭证、超时和重试策略。
 */
public class ClientConfig {

    private final String baseUrl;
    private final String accessKey;
    private final String secretKey;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxRetries;
    private final int callbackPort;
    private final String webhookSecret;

    private ClientConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.accessKey = builder.accessKey;
        this.secretKey = builder.secretKey;
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.maxRetries = builder.maxRetries;
        this.callbackPort = builder.callbackPort;
        this.webhookSecret = builder.webhookSecret;
    }

    public String getBaseUrl() { return baseUrl; }
    public String getAccessKey() { return accessKey; }
    public String getSecretKey() { return secretKey; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
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
        private String accessKey;
        private String secretKey;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        private int maxRetries = 3;
        private int callbackPort = 0;
        private String webhookSecret;

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder accessKey(String accessKey, String secretKey) {
            this.accessKey = accessKey; this.secretKey = secretKey; return this;
        }
        public Builder connectTimeout(Duration timeout) { this.connectTimeout = timeout; return this; }
        public Builder readTimeout(Duration timeout) { this.readTimeout = timeout; return this; }
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder callbackPort(int port) { this.callbackPort = port; return this; }
        public Builder webhookSecret(String secret) { this.webhookSecret = secret; return this; }

        public ClientConfig build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl 不能为空");
            }
            return new ClientConfig(this);
        }
    }
}
