package com.aria.conversation.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 配置。
 *
 * <p>{@code @CrossOrigin(origins = "${property}")} 会将整个属性值（含逗号）当作单一
 * origin，导致多域名配置失效（403 Invalid CORS request）。此类改用
 * {@link WebMvcConfigurer#addCorsMappings} 并手动 split，正确支持逗号分隔的多域名白名单。
 *
 * <p>访客公开端点（/api/v1/chat/**, /api/v1/visitor/**）通过方法级
 * {@code @CrossOrigin(origins = "*")} 单独放行，不受此处限制。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 允许的跨域来源，逗号分隔，例如：https://chat.lycodeing.cn,http://localhost:5670 */
    @Value("${app.cors.allowed-origins}")
    private String allowedOriginsConfig;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOriginsConfig.split(",\\s*");

        // 座席后台 API：仅允许白名单来源
        registry.addMapping("/api/v1/sessions/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);

        registry.addMapping("/api/v1/dashboard/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
