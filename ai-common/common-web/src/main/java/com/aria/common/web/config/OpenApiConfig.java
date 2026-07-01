package com.aria.common.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 公共配置。
 * <p>所有服务继承或扫描此包即可获得统一的 Swagger UI + Bearer Auth。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI(@Value("${spring.application.name:ai-dev-service}") String appName) {
        return new OpenAPI()
                .info(new Info()
                        .title(appName + " API")
                        .version("1.0.0")
                        .description("AI Dev Platform - " + appName))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Sa-Token JWT 认证，格式：Bearer <token>")));
    }
}
