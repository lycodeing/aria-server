package com.aria.sdk.auth.model;

/**
 * AI 模型配置的作用域。
 *
 * <p>auth-service 允许对每种作用域分别设置一个"当前激活"配置，
 * SDK 按枚举分发到对应的内部接口路径：
 *
 * <ul>
 *   <li>{@link #CHAT}       → {@code /internal/ai-models/active}</li>
 *   <li>{@link #EMBEDDING}  → {@code /internal/ai-models/active-embedding}</li>
 *   <li>{@link #ROUTER}     → {@code /internal/ai-models/active-router}</li>
 * </ul>
 *
 * @author lycodeing
 * @since 2026-07
 */
public enum ModelScope {

    /** 对话主模型（Chat Completion） */
    CHAT("/internal/ai-models/active"),

    /** 向量嵌入模型（Embedding） */
    EMBEDDING("/internal/ai-models/active-embedding"),

    /** 域路由模型（Router，低延迟低成本） */
    ROUTER("/internal/ai-models/active-router");

    private final String path;

    ModelScope(String path) { this.path = path; }

    public String path() { return path; }
}
