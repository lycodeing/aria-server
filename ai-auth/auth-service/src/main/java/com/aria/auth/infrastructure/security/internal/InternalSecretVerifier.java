package com.aria.auth.infrastructure.security.internal;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内部服务共享密钥校验器。
 *
 * <p>集中所有 {@code /internal/**} Controller 的 {@code X-Internal-Secret} 头校验逻辑，
 * 避免三处重复。启动期对配置进行 fail-fast 校验，防止占位默认值静默上线。
 *
 * <p>与 {@code auth-client} SDK 的 {@code SharedSecretInterceptor} 使用同一份配置，
 * 服务端与客户端两侧都做非空校验，配置缺失时任何一侧均会启动失败。
 *
 * @author lycodeing
 * @since 2026-07
 */
@Slf4j
@Component
public class InternalSecretVerifier {

    /**
     * 内部共享密钥，通过环境变量 {@code INTERNAL_SECRET} / {@code ARIA_INTERNAL_SECRET} 注入。
     *
     * <p>刻意不提供默认值：空值会在 {@link #validateOnStartup()} 抛异常，
     * 阻止服务端携带占位符启动导致的静默鉴权失败。
     */
    @Value("${aria.internal.secret:}")
    private String internalSecret;

    /**
     * 启动期校验：密钥不能为空。
     *
     * <p>触发条件：{@code INTERNAL_SECRET} 与 {@code ARIA_INTERNAL_SECRET} 均未设置，
     * 或显式配置为空串/空白字符。
     */
    @PostConstruct
    public void validateOnStartup() {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new IllegalStateException(
                    "aria.internal.secret 未配置，请通过环境变量 INTERNAL_SECRET 或 ARIA_INTERNAL_SECRET 注入内部共享密钥");
        }
        log.info("[InternalSecret] 内部共享密钥校验通过，长度={}", internalSecret.length());
    }

    /**
     * 校验请求头中的密钥是否匹配。
     *
     * @param providedSecret 请求头 {@code X-Internal-Secret} 携带的值
     * @return true 表示校验通过；false 表示密钥不匹配（含 null）
     */
    public boolean matches(String providedSecret) {
        return internalSecret.equals(providedSecret);
    }
}
