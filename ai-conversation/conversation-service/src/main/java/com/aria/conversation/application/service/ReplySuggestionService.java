package com.aria.conversation.application.service;

import com.aria.common.core.util.JsonUtils;
import com.aria.conversation.application.dto.ReplySuggestionDTO;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.cache.ConversationCacheKeys;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * AI 回复建议服务。
 *
 * <p>并行调用知识库向量检索（KB 来源）与 LLM 上下文推理（CONTEXT 来源），
 * 合并去重后返回建议列表，KB 结果优先置前。
 *
 * <p>Redis 2 秒幂等缓存：同一 sessionId 在 2 秒内的重复请求直接返回缓存，
 * 防止前端防抖失效时重复触发 LLM 调用。
 *
 * <p>Redis key 格式：{@code reply_suggestions:{sessionId}}，TTL 2 秒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplySuggestionService {

    private static final String KEY_PREFIX = ConversationCacheKeys.REPLY_SUGGESTIONS_PREFIX;
    private static final long   CACHE_TTL_SECONDS   = 2L;
    /** 用于上下文推理的最近消息轮数上限 */
    private static final int    MAX_HISTORY_TURNS   = 5;
    /** 上下文推理的固定置信度 */
    private static final double CONTEXT_CONFIDENCE  = 0.7;

    /**
     * 建议生成用的 LLM System Prompt。
     * 要求输出格式为按行分隔的建议文本，每条以"数字点空格"开头，便于解析。
     */
    private static final String SUGGESTION_SYSTEM_PROMPT = """
            你是一名专业的客服助手。根据对话历史和访客最新消息，生成 2~3 条可供座席直接使用的回复建议。
            要求：
            1. 每条建议独占一行，以阿拉伯数字加英文点号开头，例如"1. 建议内容"
            2. 内容简洁专业，不超过 80 字
            3. 仅输出建议列表，不要输出其他说明文字
            """;

    /** 用于并行执行 KB 检索 + LLM 推理的专线程池（2 线程，命名可观测） */
    private static final AtomicInteger WORKER_COUNTER = new AtomicInteger(0);
    private final ExecutorService suggestionExecutor = Executors.newFixedThreadPool(
            2,
            r -> {
                Thread t = new Thread(r, "reply-suggestion-worker-" + WORKER_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    private final StringRedisTemplate           redisTemplate;
    private final ConversationHistoryRepository historyRepository;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final DynamicModelFactory           modelFactory;

    @PreDestroy
    public void shutdownExecutor() {
        log.info("[Suggestion] 关闭建议生成线程池");
        suggestionExecutor.shutdown();
        try {
            if (!suggestionExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("[Suggestion] 线程池未在 5s 内终止，强制关闭");
                suggestionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            suggestionExecutor.shutdownNow();
        }
    }

    /**
     * 获取回复建议列表。
     *
     * <p>先查 Redis 缓存（2 秒内幂等），未命中则并行执行 KB 检索 + LLM 推理，
     * 合并去重后写入缓存再返回。两路并行任务各自独立降级，互不影响。
     *
     * @param sessionId   会话唯一标识
     * @param lastMessage 访客最新消息文本（用于 KB 检索 query）
     * @return 建议列表，KB 结果在前，CONTEXT 结果在后
     */
    public List<ReplySuggestionDTO> getSuggestions(String sessionId, String lastMessage) {
        // lastMessage 未传时，从历史记录取最后一条访客消息
        String resolvedMessage = (lastMessage == null || lastMessage.isBlank())
                ? resolveLastUserMessage(sessionId)
                : lastMessage;
        if (resolvedMessage == null || resolvedMessage.isBlank()) {
            log.warn("[Suggestion] 会话 {} 无有效访客消息，返回空建议", sessionId);
            return List.of();
        }
        String cacheKey = KEY_PREFIX + sessionId;

        // 幂等缓存命中：2 秒内重复请求直接返回
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                List<ReplySuggestionDTO> cachedList = JsonUtils.parseObject(
                        cached, new TypeReference<List<ReplySuggestionDTO>>() {});
                if (cachedList != null) {
                    log.debug("[Suggestion] 命中缓存 sessionId={}", sessionId);
                    return cachedList;
                }
            } catch (Exception e) {
                log.warn("[Suggestion] 缓存反序列化失败，重新生成 sessionId={}", sessionId, e);
            }
        }

        // 并行：KB 检索 + LLM 上下文推理，各自独立降级，互不影响
        CompletableFuture<List<ReplySuggestionDTO>> kbFuture = CompletableFuture
                .supplyAsync(() -> searchKb(resolvedMessage), suggestionExecutor)
                .exceptionally(e -> {
                    log.warn("[Suggestion] KB 检索失败，降级为空列表 sessionId={}", sessionId, e);
                    return List.of();
                });
        CompletableFuture<List<ReplySuggestionDTO>> contextFuture = CompletableFuture
                .supplyAsync(() -> generateContextSuggestions(sessionId, resolvedMessage), suggestionExecutor)
                .exceptionally(e -> {
                    log.warn("[Suggestion] LLM 推理失败，降级为空列表 sessionId={}", sessionId, e);
                    return List.of();
                });

        List<ReplySuggestionDTO> kbResults      = kbFuture.join();
        List<ReplySuggestionDTO> contextResults  = contextFuture.join();

        // 合并：KB 优先，CONTEXT 追加；按内容去重
        List<ReplySuggestionDTO> merged = merge(kbResults, contextResults);

        // 写入 2 秒幂等缓存
        try {
            String json = JsonUtils.toJsonString(merged);
            if (json != null) {
                redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(CACHE_TTL_SECONDS));
            }
        } catch (Exception e) {
            log.warn("[Suggestion] 缓存写入失败 sessionId={}", sessionId, e);
        }

        return merged;
    }

    // ---- 内部方法 ----

    /**
     * 从对话历史取最后一条访客消息文本，供 lastMessage 未传时兜底使用。
     * 若历史为空或无用户消息，返回 null。
     */
    private String resolveLastUserMessage(String sessionId) {
        List<ConversationMessage> history = historyRepository.findAll(sessionId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationMessage msg = history.get(i);
            if ("user".equals(msg.role()) && msg.content() != null && !msg.content().isBlank()) {
                return msg.content();
            }
        }
        return null;
    }

    /**
     * 知识库向量检索：取 topK 条 chunk，转换为 KB 来源建议。
     * confidence 取检索分数，超出 [0,1] 时截断。
     */
    private List<ReplySuggestionDTO> searchKb(String query) {
        List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(query);
        return hits.stream()
                .filter(h -> h.getContent() != null && !h.getContent().isBlank())
                .map(h -> new ReplySuggestionDTO(
                        UUID.randomUUID().toString(),
                        h.getContent().trim(),
                        Math.min(1.0, Math.max(0.0, h.getScore())),
                        "KB"))
                .toList();
    }

    /**
     * LLM 上下文推理：用最近 {@value #MAX_HISTORY_TURNS} 条消息 + 访客最新消息生成 2~3 条回复建议。
     * 解析 LLM 输出中以"数字点空格"开头的行，confidence 固定 {@value #CONTEXT_CONFIDENCE}。
     */
    private List<ReplySuggestionDTO> generateContextSuggestions(String sessionId, String lastMessage) {
        List<ConversationMessage> history = historyRepository.findAll(sessionId);
        // 过滤 tool 中间态，只保留对话轮次，再取最近 N 条
        List<ConversationMessage> recentHistory = history.stream()
                .filter(m -> m.role() != null
                        && List.of("user", "assistant", "agent").contains(m.role()))
                .toList();
        if (recentHistory.size() > MAX_HISTORY_TURNS) {
            recentHistory = recentHistory.subList(
                    recentHistory.size() - MAX_HISTORY_TURNS, recentHistory.size());
        }

        String contextBlock = recentHistory.stream()
                .map(m -> switch (m.role()) {
                    case "user"               -> "访客：" + m.content();
                    case "assistant", "agent" -> "客服：" + m.content();
                    default                   -> "";
                })
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));

        String userPrompt = String.format(
                "%s\n\n访客最新消息：%s\n\n请生成回复建议：",
                contextBlock.isBlank() ? "（无历史记录）" : contextBlock,
                lastMessage);

        String response = modelFactory.chat(List.of(ChatMessage.user(userPrompt)),
                SUGGESTION_SYSTEM_PROMPT);

        return parseSuggestions(response);
    }

    /**
     * 解析 LLM 输出：提取以"数字点/顿号 空格"开头的行。
     * 例如："1. 您好，请稍等" → content="您好，请稍等"
     */
    private List<ReplySuggestionDTO> parseSuggestions(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return List.of();
        }
        List<ReplySuggestionDTO> suggestions = new ArrayList<>();
        for (String line : llmResponse.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d+[.、]\\s*.+")) {
                String content = trimmed.replaceFirst("^\\d+[.、]\\s*", "").trim();
                if (!content.isBlank()) {
                    suggestions.add(new ReplySuggestionDTO(
                            UUID.randomUUID().toString(),
                            content,
                            CONTEXT_CONFIDENCE,
                            "CONTEXT"));
                }
            }
        }
        return suggestions;
    }

    /**
     * 合并 KB 和 CONTEXT 建议：KB 优先，CONTEXT 追加；按 content 去重（忽略大小写）。
     */
    private List<ReplySuggestionDTO> merge(List<ReplySuggestionDTO> kb,
                                           List<ReplySuggestionDTO> context) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ReplySuggestionDTO> result = new ArrayList<>(kb.size() + context.size());

        for (ReplySuggestionDTO dto : kb) {
            if (seen.add(dto.content().toLowerCase())) {
                result.add(dto);
            }
        }
        for (ReplySuggestionDTO dto : context) {
            if (seen.add(dto.content().toLowerCase())) {
                result.add(dto);
            }
        }
        return result;
    }
}
