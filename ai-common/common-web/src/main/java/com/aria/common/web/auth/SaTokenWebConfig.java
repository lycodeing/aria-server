package com.aria.common.web.auth;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器自动配置。
 *
 * <p>使用 {@code SaRouter} 方式配置路由鉴权，{@code @SaIgnore} 注解在此模式下生效。
 * 直接使用 {@code StpUtil.checkLogin()} 的写法会绕过注解检查，勿改回该写法。
 */
@AutoConfiguration
public class SaTokenWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            SaRouter.match("/**")
                    .notMatch(
                            "/api/v1/auth/login",
                            "/api/v1/auth/refresh",
                            "/api/v1/webhooks/**",
                            "/api/v1/internal/**",
                            "/internal/**",
                            "/api/v1/chat/**",
                            "/api/v1/visitor/**",
                            "/actuator/**",
                            "/swagger-ui/**",
                            "/v3/api-docs/**",
                            "/error",
                            "/favicon.ico"
                    )
                    .check(r -> StpUtil.checkLogin());
        })).addPathPatterns("/**");
    }
}
