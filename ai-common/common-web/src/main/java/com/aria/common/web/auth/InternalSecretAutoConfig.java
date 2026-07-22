package com.aria.common.web.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * {@link InternalSecretFilter} 自动装配。
 *
 * <p>注册为 Servlet Filter，优先级高于 Sa-Token 拦截器，确保鉴权在 Controller 前完成。
 * 无需各服务手动声明，引入 {@code aria-common-web} 即自动生效。
 *
 * <p>覆盖的路径：
 * <ul>
 *   <li>{@code /internal/*}     — knowledge-service、auth-service 内部接口</li>
 *   <li>{@code /api/v1/internal/*} — auth-service token 验证内部接口</li>
 * </ul>
 *
 * <p>配置项 {@code aria.internal.secret}：
 * <ul>
 *   <li>已设置 → Filter 激活，内部接口启用密钥校验。</li>
 *   <li>未设置 / 空串 → Filter 仍激活，但所有内部请求被拒绝（fail-secure）。</li>
 * </ul>
 *
 * <p>如需彻底禁用（如单元测试环境），可设置：
 * <pre>aria.internal.filter.enabled=false</pre>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "aria.internal.filter.enabled", matchIfMissing = true)
public class InternalSecretAutoConfig {

    /**
     * 注册 {@link InternalSecretFilter}，优先级高于 Spring Security / Sa-Token 过滤器链。
     * 同时覆盖 {@code /internal/*} 和 {@code /api/v1/internal/*} 两个路径前缀，
     * 确保所有内部接口（含 auth-service token 验证端点）均受同一密钥保护。
     *
     * @param internalSecret {@code aria.internal.secret} 配置值；未配置时为空串
     */
    @Bean
    public FilterRegistrationBean<InternalSecretFilter> internalSecretFilter(
            @Value("${aria.internal.secret:}") String internalSecret) {
        FilterRegistrationBean<InternalSecretFilter> registration =
                new FilterRegistrationBean<>(new InternalSecretFilter(internalSecret));
        registration.addUrlPatterns("/internal/*", "/api/v1/internal/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("internalSecretFilter");
        return registration;
    }
}
