package com.aria.conversation.application.service;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 会话摘要服务。
 *
 * <p>负责两件事：
 * <ol>
 *   <li>从 Redis 读取已缓存的摘要（{@link #getCachedSummary}）</li>
 *   <li>首次生成并以 SSE 流式推送摘要，完成后写入 Redis 缓存 7 天（{@link #streamSummary}）</li>
 * </ol>
 *
 * <p>Redis key 格式：{@code ai_summary:{sessionId}}，TTL 7 天。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryService {

    private static final String KEY_PREFIX          = "ai_summary:";
    private static final long   SUMMARY_TTL_DAYS    = 7L;
    private static final long   SSE_TIMEOUT_MS      = 3 * 60 * 1000L;
    /**
     * 摘要生成时取最近消息的条数上限。
     * 超出此上限时截取最新部分，避免超出 LLM context window 或 OOM。
     */
    private static final int    MAX_SUMMARY_MESSAGES = 100;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一名专业的客服质检助手。
            请根据以下对话记录，生成一段简洁的会话摘要（200 字以内），包含：
            1. 访客的主要问题或诉求
            2. 客服/AI 的处理结果
            3. 是否已解决（已解决/未解决/转人工）
            语言简洁，直接输出摘要文本，不需要标题或序号。
            """;

    private final StringRedisTemplate           redisTemplate;
    private final ConversationHistoryRepository historyRepository;
    private final DynamicModelFactory           modelFactory;

    /**
     * 读取已缓存的 AI 摘要。
     *
     * @param sessionId 会话唯一标识
     * @return 摘要文本，未生成时返回 {@code null}
     */
    public String getCachedSummary(String sessionId) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
    }

    /**
     * 首次生成会话摘要，以 SSE 流式推送 token，完成后写入 Redis 缓存。
     *
     * <p>若 Redis 中已有缓存，直接以单条 {@code cached} 事件推送缓存内容并结束，
     * 避免重复调用 LLM。
     *
     * <p>历史消息截取最近 {@value #MAX_SUMMARY_MESSAGES} 条，防止超出 LLM context window。
     *
     * @param sessionId 会话唯一标识
     * @return Spring MVC {@link SseEmitter}
     */
    public SseEmitter streamSummary(String sessionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 命中缓存：直接推送缓存内容，避免重复 LLM 调用
        String cached = getCachedSummary(sessionId);
        if (cached != null) {
            try {
                emitter.send(SseEmitter.event().name("cached").data(cached));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            } catch (IOException e) {
                log.debug("[AiSummary] 缓存推送失败 sessionId={}", sessionId, e);
            }
            emitter.complete();
            return emitter;
        }

        // 读取历史消息，截取最近 MAX_SUMMARY_MESSAGES 条防止 OOM / 超 context window
        List<ConversationMessage> history = historyRepository.findAll(sessionId);
        if (history.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("暂无对话记录，无法生成摘要"));
            } catch (IOException e) {
                log.debug("[AiSummary] 错误事件推送失败 sessionId={}", sessionId, e);
            }
            emitter.complete();
            return emitter;
        }

        // 过滤 tool 中间态，取最近 N 条对话消息
        List<ConversationMessage> dialogHistory = history.stream()
                .filter(m -> m.role() != null
                        && List.of("user", "assistant", "agent").contains(m.role()))
                .toList();
        if (dialogHistory.size() > MAX_SUMMARY_MESSAGES) {
            dialogHistory = dialogHistory.subList(
                    dialogHistory.size() - MAX_SUMMARY_MESSAGES, dialogHistory.size());
        }

        String contextText = buildContextText(dialogHistory);
        List<ChatMessage> messages = List.of(ChatMessage.user(contextText));
        StringBuilder fullSummary = new StringBuilder();

        Flux<String> tokenFlux = modelFactory.streamChat(messages, SUMMARY_SYSTEM_PROMPT);

        tokenFlux.subscribe(
                token -> {
                    fullSummary.append(token);
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        log.debug("[AiSummary] token 推送失败 sessionId={}", sessionId, e);
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.warn("[AiSummary] LLM 生成失败 sessionId={}", sessionId, error);
                    try {
                        emitter.send(SseEmitter.event().name("error").data("生成摘要失败，请稍后重试"));
                    } catch (IOException e) {
                        log.debug("[AiSummary] 错误事件推送失败 sessionId={}", sessionId, e);
                    }
                    emitter.completeWithError(error);
                },
                () -> {
                    String summary = fullSummary.toString().trim();
                    if (!summary.isBlank()) {
                        redisTemplate.opsForValue().set(
                                KEY_PREFIX + sessionId,
                                summary,
                                Duration.ofDays(SUMMARY_TTL_DAYS));
                        log.debug("[AiSummary] 摘要已缓存 sessionId={} len={}", sessionId, summary.length());
                    }
                    try {
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    } catch (IOException e) {
                        log.debug("[AiSummary] done 事件推送失败 sessionId={}", sessionId, e);
                    }
                    emitter.complete();
                }
        );

        emitter.onError(e -> log.debug("[AiSummary] SSE 连接错误 sessionId={}", sessionId, e));
        return emitter;
    }

    // ---- 内部工具 ----

    /**
     * 将消息列表拼接为供 LLM 理解的对话上下文文本。
     */
    private String buildContextText(List<ConversationMessage> history) {
        return history.stream()
                .map(m -> switch (m.role()) {
                    case "user"               -> "访客：" + m.content();
                    case "assistant"          -> "AI：" + m.content();
                    case "agent"              -> "座席：" + m.content();
                    default                   -> "";
                })
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
