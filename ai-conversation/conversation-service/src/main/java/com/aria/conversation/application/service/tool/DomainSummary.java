package com.aria.conversation.application.service.tool;

/**
 * 服务域摘要值对象，仅含 application 层所需的最小字段。
 *
 * <p>替代 {@code DomainDO}（持久化实体）在应用层的直接流转，
 * 隔离 infrastructure 层的 MyBatis-Plus 注解泄漏。
 *
 * @param code        域唯一标识，如 "ecommerce"
 * @param description 域描述，用于 system prompt 和工具说明
 */
public record DomainSummary(String code, String description) {}
