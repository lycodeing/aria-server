package com.aria.common.web.ai;

/**
 * AI 模型配置值对象（不可变）。
 * 由 AiModelConfigProvider 提供，包含已解密的 API Key，不得序列化到日志或响应。
 *
 * @param apiProtocol API 协议类型：OPENAI_COMPATIBLE / ANTHROPIC / GEMINI
 */
public record AiModelConfig(
        Long   id,
        String name,
        String provider,
        String apiProtocol,
        String baseUrl,
        /** ⚠️ 已解密明文，禁止打印到日志或序列化到 HTTP 响应 */
        String apiKey,
        String modelName,
        /** 采样温度，有效范围 0.0–2.0 */
        double temperature,
        int    maxTokens,
        /** 请求超时秒数 */
        int    timeoutSec
) {
    /** 是否为 OpenAI 兼容协议 */
    public boolean isOpenAiCompatible() {
        return "OPENAI_COMPATIBLE".equals(apiProtocol);
    }

    /** 是否为 Anthropic 协议 */
    public boolean isAnthropic() {
        return "ANTHROPIC".equals(apiProtocol);
    }
}
