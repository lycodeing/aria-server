package com.aria.conversation.infrastructure.ai.mcp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 基础设施自动配置，负责注册 {@link McpProperties} 为 Spring Bean。
 *
 * <p>{@link McpProperties} 去掉了 {@code @Component} 注解（避免双重注册），
 * 改由本配置类通过 {@code @EnableConfigurationProperties} 统一注册。
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfiguration {}
