package com.aria.common.web.ai;

/**
 * AI 模型作用域缺省参数。
 *
 * <p>为 CHAT / EMBEDDING / ROUTER 三种作用域提供统一的 temperature、maxTokens、
 * timeoutSec 缺省值。<strong>所有</strong> 需要为 {@link AiModelConfig} 空字段兜底
 * 的地方都必须通过此枚举读取，避免魔法值散落在服务端 Controller 与客户端 Provider 两侧。
 *
 * <p>缺省语义：
 * <ul>
 *   <li>{@link #CHAT} — 对话主模型，倾向创造性，超时 60s</li>
 *   <li>{@link #EMBEDDING} — 向量嵌入，确定性输出，超时 30s；maxTokens=0 表示不限制</li>
 *   <li>{@link #ROUTER} — 域路由小模型，低延迟低成本，超时 5s，输出 32 tokens</li>
 * </ul>
 *
 * @author lycodeing
 * @since 2026-07
 */
public enum AiModelScopeDefaults {

    /** 对话主模型缺省参数。 */
    CHAT(0.7D, 2048, 60),

    /** 向量嵌入模型缺省参数。 */
    EMBEDDING(0.0D, 0, 30),

    /** 域路由小模型缺省参数。 */
    ROUTER(0.0D, 32, 5);

    private final Double defaultTemperature;
    private final Integer defaultMaxTokens;
    private final Integer defaultTimeoutSec;

    AiModelScopeDefaults(Double defaultTemperature, Integer defaultMaxTokens, Integer defaultTimeoutSec) {
        this.defaultTemperature = defaultTemperature;
        this.defaultMaxTokens = defaultMaxTokens;
        this.defaultTimeoutSec = defaultTimeoutSec;
    }

    public Double defaultTemperature() { return defaultTemperature; }
    public Integer defaultMaxTokens() { return defaultMaxTokens; }
    public Integer defaultTimeoutSec() { return defaultTimeoutSec; }
}
