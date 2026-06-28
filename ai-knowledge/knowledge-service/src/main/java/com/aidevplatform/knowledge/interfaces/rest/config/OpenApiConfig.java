package com.aidevplatform.knowledge.interfaces.rest.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 文档配置。
 * 访问地址：http://localhost:8081/swagger-ui/index.html
 * 内部接口 /internal/** 不在文档中暴露（application.yml paths-to-exclude 配置）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI knowledgeOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("智能客服 - 知识库服务 API")
                .description("文档摄取、知识库管理、混合检索接口文档")
                .version("v1.0.0")
                .contact(new Contact()
                    .name("AI Platform Team")
                    .email("ai-platform@example.com")));
    }
}
