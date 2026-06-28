package com.aidevplatform.common.web.auth;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Sa-Token 拦截器配置。
 * <p>注册全局认证拦截器，排除登录、Webhook、内部回调等白名单路径。
 * 各服务可通过 application.yml 的 sa-token 配置自定义。
 */
@Configuration
public class SaTokenWebConfig implements WebMvcConfigurer {

    /**
     * 全局白名单路径（无需认证）。
     */
    private static final List<String> WHITELIST = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/webhooks/**",
            "/api/v1/internal/**",
            "/internal/**",
            "/actuator/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/error",
            "/favicon.ico"
    );

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(WHITELIST);
    }
}
