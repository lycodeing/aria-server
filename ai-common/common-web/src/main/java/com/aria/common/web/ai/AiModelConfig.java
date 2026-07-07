package com.aria.common.web.ai;

/**
 * AI 模型配置值对象（不可变）。
 *
 * <p>由 {@link AiModelConfigProvider} 提供，Provider 必须保证所有字段非空：
 * 空字段一律使用 {@link AiModelScopeDefaults} 对应作用域的缺省值兜底。
 *
 * <p>字段全部采用包装类型（阿里巴巴 Java 开发手册【强制】"所有 POJO 类属性必须使用
 * 包装数据类型"），同时保留"Provider 层保证非空"的语义约束。
 *
 * <p><strong>安全：{@link #apiKey} 为解密后明文，禁止打印到日志或序列化到 HTTP 响应。</strong>
 *
 * @param id          配置主键
 * @param name        配置名称（管理台展示）
 * @param provider    模型供应商标识（如 openai / anthropic）
 * @param apiProtocol API 协议类型：OPENAI_COMPATIBLE / ANTHROPIC / GEMINI
 * @param baseUrl     模型 API 基础地址
 * @param apiKey      已解密明文 API Key
 * @param modelName   模型名称（如 gpt-4o / claude-3-opus）
 * @param temperature 采样温度，有效范围 0.0–2.0
 * @param maxTokens   最大输出 token 数；0 表示不限制
 * @param timeoutSec  请求超时秒数
 * @author lycodeing
 * @since 2026-07
 */
public record AiModelConfig(
        Long id,
        String name,
        String provider,
        String apiProtocol,
        String baseUrl,
        String apiKey,
        String modelName,
        Double temperature,
        Integer maxTokens,
        Integer timeoutSec
) {
    /** 是否为 OpenAI 兼容协议。 */
    public boolean isOpenAiCompatible() {
        return "OPENAI_COMPATIBLE".equals(apiProtocol);
    }

    /** 是否为 Anthropic 协议。 */
    public boolean isAnthropic() {
        return "ANTHROPIC".equals(apiProtocol);
    }
}
