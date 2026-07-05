package com.aria.conversation.infrastructure.ai;

import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dynamic LangChain4j Model Factory.
 * Replaces DynamicAiClient. Uses LlmModelBuilder strategy to eliminate switch(apiProtocol).
 * Caffeine-based caches keyed by config hash support hot-swapping without restart.
 */
@Slf4j
@Component
public class DynamicModelFactory {

    private final AiModelConfigProvider configProvider;
    private final List<LlmModelBuilder> builders;

    private final Cache<String, ChatModel> chatCache = Caffeine.newBuilder()
            .maximumSize(10).expireAfterAccess(30, TimeUnit.MINUTES).build();
    private final Cache<String, StreamingChatModel> streamingCache = Caffeine.newBuilder()
            .maximumSize(10).expireAfterAccess(30, TimeUnit.MINUTES).build();

    public DynamicModelFactory(AiModelConfigProvider configProvider,
                                List<LlmModelBuilder> builders) {
        this.configProvider = configProvider;
        this.builders = builders;
    }

    /**
     * Streaming chat — drop-in replacement for DynamicAiClient.streamChat().
     * Converts project ChatMessage + systemPrompt to LangChain4j types internally.
     */
    public Flux<String> streamChat(List<ChatMessage> messages, String systemPrompt) {
        AiModelConfig cfg = configProvider.getActive();
        StreamingChatModel model = getStreamingChatModel();
        log.debug("[AI] streamChat model={} protocol={}", cfg.modelName(), cfg.apiProtocol());

        List<dev.langchain4j.data.message.ChatMessage> lc4jMessages =
                toLangChain4jMessages(messages, systemPrompt);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        model.chat(lc4jMessages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) { sink.tryEmitNext(token); }
            @Override
            public void onCompleteResponse(ChatResponse response) { sink.tryEmitComplete(); }
            @Override
            public void onError(Throwable error) {
                log.warn("[AI] 流式对话错误 model={}", cfg.modelName(), error);
                sink.tryEmitError(error);
            }
        });
        return sink.asFlux().filter(s -> !s.isEmpty());
    }

    /**
     * Blocking chat — drop-in replacement for DynamicAiClient.chat().
     * ⚠️ Must be called on boundedElastic thread only.
     */
    public String chat(List<ChatMessage> messages, String systemPrompt) {
        AiModelConfig cfg = configProvider.getActive();
        log.debug("[AI] chat model={} protocol={}", cfg.modelName(), cfg.apiProtocol());
        return getChatModel().chat(toLangChain4jMessages(messages, systemPrompt))
                .aiMessage().text();
    }

    /** Returns cached ChatModel for the current active config. */
    public ChatModel getChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        return chatCache.get(configHash(cfg), k -> {
            log.info("[AI] Building ChatModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildChatModel(cfg);
        });
    }

    /** Returns cached StreamingChatModel for the current active config. */
    public StreamingChatModel getStreamingChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        return streamingCache.get(configHash(cfg), k -> {
            log.info("[AI] Building StreamingModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildStreamingModel(cfg);
        });
    }

    /** Returns the current chat config hash for diagnostics/logging. */
    public String currentConfigHash() {
        return configHash(configProvider.getActive());
    }

    // NOTE: getRouterModel() is NOT implemented here — it depends on
    // AiModelConfigProvider.getActiveRouter() which is added in Phase 2.

    private LlmModelBuilder resolveBuilder(AiModelConfig cfg) {
        return builders.stream()
                .filter(b -> b.supports(cfg.apiProtocol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "[AI] No LlmModelBuilder for protocol: " + cfg.apiProtocol()));
    }

    private String configHash(AiModelConfig cfg) {
        String input = cfg.baseUrl() + cfg.apiKey() + cfg.modelName() + cfg.apiProtocol();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec, this never throws
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLangChain4jMessages(
            List<ChatMessage> messages, String systemPrompt) {
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(SystemMessage.from(systemPrompt));
        }
        for (ChatMessage m : messages) {
            result.add("assistant".equals(m.role())
                    ? AiMessage.from(m.content())
                    : UserMessage.from(m.content()));
        }
        return result;
    }
}
