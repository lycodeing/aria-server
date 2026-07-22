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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 动态 LangChain4j 模型工厂。
 *
 * <p>替代原 {@code DynamicAiClient}，通过 {@link LlmModelBuilder} 策略模式消除
 * {@code switch(apiProtocol)} 分支。使用 Caffeine 缓存按配置 hash 缓存模型实例，
 * 支持运行期热切换配置（不同 baseUrl/modelName/apiKey 各自独立实例），无需重启。
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
    private final Cache<String, ChatModel> routerCache = Caffeine.newBuilder()
            .maximumSize(5).expireAfterAccess(30, TimeUnit.MINUTES).build();

    public DynamicModelFactory(AiModelConfigProvider configProvider,
                                List<LlmModelBuilder> builders) {
        this.configProvider = configProvider;
        this.builders = builders;
    }

    /**
     * 流式对话 — {@code DynamicAiClient.streamChat()} 的替代方法。
     * 内部将项目 {@link ChatMessage} 和 systemPrompt 转换为 LangChain4j 类型。
     *
     * @param messages     对话消息列表
     * @param systemPrompt 系统提示词（可为 null）
     * @return AI 回复 token 流
     */
    public Flux<String> streamChat(List<ChatMessage> messages, String systemPrompt) {
        List<dev.langchain4j.data.message.ChatMessage> lc4jMessages =
                toLangChain4jMessages(messages, systemPrompt);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        getStreamingChatModel().chat(lc4jMessages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) { sink.tryEmitNext(token); }
            @Override
            public void onCompleteResponse(ChatResponse response) { sink.tryEmitComplete(); }
            @Override
            public void onError(Throwable error) {
                log.warn("[AI] 流式对话错误", error);
                sink.tryEmitError(error);
            }
        });
        return sink.asFlux().filter(s -> !s.isEmpty());
    }

    /**
     * 阻塞式对话 — {@code DynamicAiClient.chat()} 的替代方法。
     * ⚠️ 必须在 boundedElastic 线程上调用。
     *
     * @param messages     对话消息列表
     * @param systemPrompt 系统提示词（可为 null）
     * @return AI 完整回复文本
     */
    public String chat(List<ChatMessage> messages, String systemPrompt) {
        return getChatModel().chat(toLangChain4jMessages(messages, systemPrompt))
                .aiMessage().text();
    }

    /**
     * 带独立请求时限的阻塞式对话。
     * 用于不应受全局模型超时拖延的低优先级辅助调用。
     *
     * @param messages     对话消息列表
     * @param systemPrompt 系统提示词（可为 null）
     * @param timeout      请求时限
     * @return AI 完整回复文本
     */
    public String chat(List<ChatMessage> messages, String systemPrompt, Duration timeout) {
        return getChatModel(timeout).chat(toLangChain4jMessages(messages, systemPrompt))
                .aiMessage().text();
    }

    /**
     * 获取当前活跃配置对应的缓存 ChatModel。
     *
     * @return 缓存的 ChatModel 实例
     */
    public ChatModel getChatModel() {
        return getChatModel(configProvider.getActive());
    }

    /**
     * 获取指定请求时限对应的缓存 ChatModel。
     * 用于需要比全局模型配置更短 deadline 的独立调用场景。
     *
     * @param timeout 请求时限
     * @return 缓存的 ChatModel 实例
     */
    public ChatModel getChatModel(java.time.Duration timeout) {
        AiModelConfig active = configProvider.getActive();
        AiModelConfig cfg = new AiModelConfig(
                active.id(), active.name(), active.provider(), active.apiProtocol(),
                active.baseUrl(), active.apiKey(), active.modelName(), active.temperature(),
                active.maxTokens(), Math.toIntExact(timeout.toSeconds()));
        return getChatModel(cfg);
    }

    private ChatModel getChatModel(AiModelConfig cfg) {
        return chatCache.get(configHash(cfg), k -> {
            log.info("[AI] Building ChatModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildChatModel(cfg);
        });
    }

    /**
     * 获取当前活跃配置对应的缓存 StreamingChatModel。
     *
     * @return 缓存的 StreamingChatModel 实例
     */
    public StreamingChatModel getStreamingChatModel() {
        AiModelConfig cfg = configProvider.getActive();
        return streamingCache.get(configHash(cfg), k -> {
            log.info("[AI] Building StreamingModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildStreamingModel(cfg);
        });
    }

    /**
     * 获取当前活跃配置的 hash 值，用于诊断和日志。
     *
     * @return 配置 hash 字符串
     */
    public String currentConfigHash() {
        return configHash(configProvider.getActive());
    }

    /**
     * ROUTER 小模型（用于 DomainRoutingService 域路由判断）。
     * Caffeine 缓存，routerCache 最多 5 个实例。
     *
     * @return 缓存的 Router ChatModel 实例
     */
    public ChatModel getRouterModel() {
        AiModelConfig cfg = configProvider.getActiveRouter();
        return routerCache.get(configHash(cfg), k -> {
            log.info("[AI] Building RouterModel protocol={} model={}", cfg.apiProtocol(), cfg.modelName());
            return resolveBuilder(cfg).buildChatModel(cfg);
        });
    }

    private LlmModelBuilder resolveBuilder(AiModelConfig cfg) {
        return builders.stream()
                .filter(b -> b.supports(cfg.apiProtocol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "[AI] No LlmModelBuilder for protocol: " + cfg.apiProtocol()));
    }

    /**
     * 生成配置缓存 key — 包含 baseUrl、modelName、apiProtocol 和脱敏 apiKey。
     * 使用 "|" 分隔符防止字段粘连导致碰撞；apiKey 只取首尾各 4 字符以降低彩虹表风险。
     *
     * @param cfg AI 模型配置
     * @return SHA-256 hash 字符串
     */
    private String configHash(AiModelConfig cfg) {
        // apiKey 必须用原始值参与 hash，不可脱敏；
        // 脱敏（首尾各 4 位）会使中间部分不同的两个 Key 产生相同 hash，
        // 导致切换账号时命中缓存并持续使用旧 Key 对应的模型实例。
        String input = String.join("|",
                cfg.baseUrl() != null ? cfg.baseUrl() : "",
                cfg.modelName() != null ? cfg.modelName() : "",
                cfg.apiProtocol() != null ? cfg.apiProtocol() : "",
                String.valueOf(cfg.timeoutSec()),
                String.valueOf(cfg.timeoutSec()),
                cfg.apiKey() != null ? cfg.apiKey() : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 由 JVM 规范保证可用，此处不会抛出
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 脱敏 apiKey（仅用于日志）：保留首尾各 4 个字符，中间用 "***" 替代。
     * 长度不足 8 的 apiKey 直接返回原值（通常为测试占位符）。
     *
     * <p>⚠️ 禁止用于缓存 key 计算，缓存 key 必须使用 {@link #configHash} 中的原始值。
     */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null) {
            return "";
        }
        if (apiKey.length() <= 8) {
            return apiKey;
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLangChain4jMessages(
            List<ChatMessage> messages, String systemPrompt) {
        List<dev.langchain4j.data.message.ChatMessage> result = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(SystemMessage.from(systemPrompt));
        }
        for (ChatMessage m : messages) {
            // assistant 与 agent（人工座席）在 LLM 语境中均视为 assistant 轮次；
            // agent 仅用于前端区分"人工客服"与"AI 助手"，对模型无语义差异
            result.add(switch (m.role()) {
                case "assistant", "agent" -> AiMessage.from(m.content());
                case "system"             -> SystemMessage.from(m.content());
                default                   -> UserMessage.from(m.content());
            });
        }
        return result;
    }
}
