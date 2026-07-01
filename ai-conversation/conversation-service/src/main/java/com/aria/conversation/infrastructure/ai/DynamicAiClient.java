package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 动态 AI 客户端。
 * 根据 {@link AiModelConfigProvider} 提供的当前激活配置，
 * 选择对应的 {@link AiProtocolHandler} 发起请求，支持运行时热切换模型。
 *
 * <p>替换原 {@code CtyunAiClient}（该类将在 Task 8 删除）。
 */
@Slf4j
@Component
public class DynamicAiClient {

    private final AiModelConfigProvider configProvider;
    private final Map<String, AiProtocolHandler> handlers;

    public DynamicAiClient(AiModelConfigProvider configProvider) {
        this.configProvider = configProvider;
        this.handlers = Map.of(
                "OPENAI_COMPATIBLE", new OpenAiCompatibleHandler(),
                "ANTHROPIC",         new AnthropicHandler()
        );
    }

    /**
     * 流式对话：返回 Flux，每个元素为原始 chunk JSON 字符串。
     * 调用方通过 {@link #extractDeltaContent(String)} 提取文本内容。
     */
    public Flux<String> streamChat(List<ChatMessage> messages, String systemPrompt) {
        AiModelConfig config = configProvider.getActive();
        AiProtocolHandler handler = resolveHandler(config);
        log.debug("[AI] streamChat model={} protocol={}", config.modelName(), config.apiProtocol());
        return handler.streamChat(config, messages, systemPrompt);
    }

    /**
     * 非流式对话：返回完整回复文本。
     * ⚠️ 内部使用 .block()，仅限 Spring MVC 阻塞线程调用。
     */
    public String chat(List<ChatMessage> messages, String systemPrompt) {
        AiModelConfig config = configProvider.getActive();
        AiProtocolHandler handler = resolveHandler(config);
        return handler.chat(config, messages, systemPrompt);
    }

    /**
     * 从流式 chunk JSON 中提取 delta content 文本。
     * 使用当前激活配置对应的协议处理器解析。
     */
    public String extractDeltaContent(String chunkJson) {
        AiModelConfig config = configProvider.getActive();
        return resolveHandler(config).extractDeltaContent(chunkJson);
    }

    private AiProtocolHandler resolveHandler(AiModelConfig config) {
        AiProtocolHandler handler = handlers.get(config.apiProtocol());
        if (handler == null) {
            log.warn("[AI] 未知协议 {}，降级使用 OPENAI_COMPATIBLE", config.apiProtocol());
            handler = handlers.get("OPENAI_COMPATIBLE");
        }
        return handler;
    }
}
