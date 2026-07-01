package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 协议处理器策略接口。
 * 每种 api_protocol 对应一个实现，负责请求构造和响应解析。
 * 新增供应商时只需新增实现类，无需修改主流程。
 */
public interface AiProtocolHandler {

    /** 返回本处理器支持的 api_protocol 值，如 OPENAI_COMPATIBLE */
    String protocol();

    /**
     * 流式对话：返回原始 chunk 字符串 Flux。
     * 调用方通过 {@link #extractDeltaContent(String)} 提取文本内容。
     */
    Flux<String> streamChat(AiModelConfig config, List<ChatMessage> messages, String systemPrompt);

    /**
     * 非流式对话：返回完整回复文本。
     * ⚠️ 内部使用 .block()，仅限 Spring MVC 阻塞线程调用。
     */
    String chat(AiModelConfig config, List<ChatMessage> messages, String systemPrompt);

    /**
     * 从流式 chunk JSON 中提取 delta content 文本。
     * chunk 格式异常或无内容时返回空字符串，不抛出异常。
     */
    String extractDeltaContent(String chunkJson);
}
