package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 动态 AI 客户端。
 * 根据 {@link AiModelConfigProvider} 提供的当前激活配置，
 * 选择对应的 {@link AiProtocolHandler} 发起请求，支持运行时热切换模型。
 *
 * <p>Handler 通过 Spring 注入（@Component），不再手动 new，
 * 确保 ObjectMapper、WebClient 等依赖由容器统一管理。
 *
 * <p>热切换安全：{@link #streamChat} 在订阅前锁定 handler 和 config，
 * 流式传输全程使用同一 handler 完成 chunk 解析，不会因中途切换协议导致解析错乱。
 */
@Slf4j
@Component
public class DynamicAiClient {

    private final AiModelConfigProvider configProvider;
    /** protocol → handler 映射，启动时由 Spring 注入所有 @Component Handler 构建 */
    private final Map<String, AiProtocolHandler> handlers;

    public DynamicAiClient(AiModelConfigProvider configProvider,
                           List<AiProtocolHandler> handlerList) {
        this.configProvider = configProvider;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(AiProtocolHandler::protocol, h -> h));
        log.info("[AI] 已加载协议处理器: {}", this.handlers.keySet());
    }

    /**
     * 流式对话：锁定当前激活 handler 后发起请求，在同一 handler 内完成 chunk 解析。
     * 返回的 Flux 每个元素为已提取的 delta 文本（空串已过滤），调用方直接消费文本内容。
     *
     * <p>热切换安全：handler 在订阅前捕获到闭包，整条流使用同一协议解析，
     * 不受流传输期间配置变更影响。
     */
    public Flux<String> streamChat(List<ChatMessage> messages, String systemPrompt) {
        AiModelConfig config = configProvider.getActive();
        AiProtocolHandler handler = resolveHandler(config);
        log.debug("[AI] streamChat model={} protocol={}", config.modelName(), config.apiProtocol());
        // handler 捕获到闭包，整条流内协议固定
        return handler.streamChat(config, messages, systemPrompt)
                .map(handler::extractDeltaContent)
                .filter(content -> !content.isEmpty());
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

    private AiProtocolHandler resolveHandler(AiModelConfig config) {
        AiProtocolHandler handler = handlers.get(config.apiProtocol());
        if (handler == null) {
            log.warn("[AI] 未知协议 {}，降级使用 OPENAI_COMPATIBLE", config.apiProtocol());
            handler = handlers.get("OPENAI_COMPATIBLE");
        }
        return handler;
    }
}
