package com.aria.knowledge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding 服务运维参数配置。
 *
 * <p>baseUrl / apiKey / modelName 由 {@code AiModelConfigProvider.getActiveEmbedding()} 动态提供，
 * 统一在 auth-service 后台管理，不在此处硬编码。
 *
 * <p>此类仅保留与业务无关的运维调优参数：
 * <ul>
 *   <li>{@code batchSize}    — 单次 /embeddings 请求最大文本条数（防超时/防 OOM）</li>
 *   <li>{@code timeoutSeconds} — HTTP 请求超时时间（秒）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "knowledge.embedding")
public record EmbeddingProperties(

        /**
         * 单次 /embeddings 请求最大文本条数。
         * BGE-M3 推荐 32 以内，超出时按此值分批调用。
         */
        @DefaultValue("32") int batchSize,

        /**
         * HTTP 请求超时秒数。
         * 大批次或模型冷启动可适当调大。
         */
        @DefaultValue("30") int timeoutSeconds
) {}
