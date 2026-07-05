package com.aria.conversation.infrastructure.dit.config;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * 领域配置（只读，从 cs_domain 映射，含完整意图列表，存入 Redis 缓存）。
 *
 * @param code              领域标识，如 "ecommerce"
 * @param name              领域名称
 * @param description       领域描述（供域路由 prompt 使用）
 * @param systemPromptAddon 追加到 system prompt 的专属说明
 * @param knowledgeBaseId   专属知识库 ID，null 使用全局
 * @param intents           意图列表（按 sort_order 排序）
 */
public record DomainConfig(
        String code,
        String name,
        String description,
        String systemPromptAddon,
        Long knowledgeBaseId,
        List<IntentConfig> intents
) implements Serializable {

    /** 按 intentCode 查找意图，不存在返回 empty */
    public Optional<IntentConfig> findIntent(String intentCode) {
        return intents.stream()
                .filter(i -> i.code().equals(intentCode))
                .findFirst();
    }
}
