package com.aria.knowledge.interfaces.rest.config;

import com.aria.knowledge.domain.model.DocStatus;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 参数转换配置。
 *
 * <p>注册大小写不敏感的 {@link DocStatus} 枚举转换器，
 * 使前端可以传 "published"、"PUBLISHED" 等任意大小写形式。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        // String → DocStatus：统一转大写后匹配枚举常量，与原有手动转换行为一致
        registry.addConverter(String.class, DocStatus.class, value -> {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return DocStatus.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("无效的文档状态值：" + value);
            }
        });
    }
}
