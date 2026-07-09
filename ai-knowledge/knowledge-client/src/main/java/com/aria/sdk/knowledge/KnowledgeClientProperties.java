package com.aria.sdk.knowledge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@link KnowledgeClient} 配置属性（绑定 {@code knowledge.client.*}）。
 *
 * <p>{@code access-key} / {@code secret-key} 未配置时通过 {@code @NotBlank}
 * 在 Bean 绑定阶段抛 {@code BindException}，实现启动期 fail-fast。
 *
 * <p>注意：{@code access-key} 和 {@code secret-key} 使用 {@link ToString.Exclude}
 * 防止密钥明文出现在日志或 Spring condition 报告输出中。
 *
 * @author lycodeing
 * @since 2026-07
 */
@Data
@Validated
@ConfigurationProperties(prefix = "knowledge.client")
public class KnowledgeClientProperties {

    /** 知识库服务内网地址，默认 http://localhost:8084 */
    @NotBlank
    private String baseUrl = "http://localhost:8084";

    /** AK/SK 签名密钥对中的 Access Key。 */
//    @NotBlank
    @ToString.Exclude
    private String accessKey;

    /** AK/SK 签名密钥对中的 Secret Key。 */
//    @NotBlank
    @ToString.Exclude
    private String secretKey;

    /** 内网共享密钥（SHARED_SECRET 模式），与 AK/SK 模式互斥。 */
    @ToString.Exclude
    private String sharedSecret;

    /** 连接超时（毫秒），默认 3000。 */
    @Min(100)
    private int connectTimeoutMs = 3000;

    /** 读取超时（毫秒），默认 10000。 */
    @Min(100)
    private int readTimeoutMs = 10000;

    /** 最大重试次数，默认 3。 */
    @Min(0)
    private int maxRetries = 3;
}