package com.aria.conversation.application.service;

import com.aria.common.core.util.JsonUtils;
import com.aria.conversation.application.dto.ReplySuggestionDTO;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.cache.ConversationCacheKeys;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * AI 回复建议服务。
 *
 * <p>并行调用知识库向量检索（KB 来源）与 LLM 上下文推理（CONTEXT 来源），
 * 合并去重后返回建议列表，KB 结果优先置前。
 *
 * <p>Redis 缓存：同一 sessionId + 消息内容在 30 秒内重复请求直接返回缓存，
 * 新消息到来时自动命中不同 key，无需手动失效。
 *
 * <p>Redis key 格式：{@code reply_suggestions:{sessionId}:{msgHash}}，TTL 30 秒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplySuggestionService {

    private static final String KEY_PREFIX = ConversationCacheKeys.REPLY_SUGGESTIONS_PREFIX;
    /**
     * 同一消息的建议缓存 30 秒
     */
    private static final long CACHE_TTL_SECONDS = 30L;
    /**
     * 单路任务最长等待时间（KB 检索 + LLM 推理各自独立计时）
     */
    private static final long TASK_TIMEOUT_SECONDS = 15L;
    /**
     * 建议生成并发任务上限，饱和时立即降级而不是无限创建线程。
     */
    private static final int MAX_WORKER_THREADS = 16;
    /**
     * 排队等待执行的任务上限。
     */
    private static final int MAX_QUEUED_TASKS = 32;
    /**
     * 用于上下文推理的最近消息轮数上限
     */
    private static final int MAX_HISTORY_TURNS = 5;
    /**
     * 上下文推理的固定置信度
     */
    private static final double CONTEXT_CONFIDENCE = 0.7;

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

    /**
     * I/O 密集型任务（KB HTTP + LLM 网络请求）使用有界线程池：
     * 线程与队列均设上限，饱和时由调用方降级，避免超时请求堆积耗尽资源。
     */
    private static final AtomicInteger WORKER_COUNTER = new AtomicInteger(0);
    private final ExecutorService suggestionExecutor = new ThreadPoolExecutor(
            4,
            MAX_WORKER_THREADS,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_TASKS),
            r -> {
                Thread t = new Thread(r, "reply-suggestion-worker-" + WORKER_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private final StringRedisTemplate redisTemplate;
    private final ConversationHistoryRepository historyRepository;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final DynamicModelFactory modelFactory;

    @PreDestroy
    public void shutdownExecutor() {
        log.info("[Suggestion] 关闭建议生成线程池");
        suggestionExecutor.shutdown();
        try {
            if (!suggestionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
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
     * <p>流程：
     * <ol>
     *   <li>若未传最新消息，从历史中解析最后一条访客消息</li>
     *   <li>查 Redis 缓存（key 含消息 SHA-256，30 秒内相同消息命中缓存）</li>
     *   <li>未命中则并行执行 KB 检索和 LLM 推理；历史读取仅归属 LLM 分支，失败时独立降级</li>
     *   <li>合并去重后写入缓存再返回</li>
     * </ol>
     *
     * @param sessionId   会话唯一标识
     * @param lastMessage 访客最新消息文本（null 时从历史自动取最后一条）
     * @return 建议列表，KB 结果在前，CONTEXT 结果在后
     */
    public List<ReplySuggestionDTO> getSuggestions(String sessionId, String lastMessage) {
        List<ConversationMessage> history = null;
        String resolvedMessage = lastMessage;
        if (StringUtils.isBlank(resolvedMessage)) {
            try {
                // 未传消息时必须从历史中确定 KB 查询文本；该历史也复用给上下文推理。
                history = historyRepository.findAll(sessionId);
                resolvedMessage = resolveLastUserMessage(history);
            } catch (Exception e) {
                log.warn("[Suggestion] 历史读取失败，无法确定访客消息 sessionId={}", sessionId, e);
                return List.of();
            }
        }
        if (StringUtils.isBlank(resolvedMessage)) {
            log.warn("[Suggestion] 会话 {} 无有效访客消息，返回空建议", sessionId);
            return List.of();
        }

        // 请求带 lastMessage 时无需依赖历史即可命中缓存，避免 Redis 历史读取影响缓存可用性。
        String cacheKey = buildCacheKey(sessionId, resolvedMessage);
        List<ReplySuggestionDTO> cached = readCache(cacheKey, sessionId);
        if (cached != null) {
            return cached;
        }

        String message = resolvedMessage;
        CompletableFuture<List<ReplySuggestionDTO>> kbFuture = runAsync(
                "KB 检索", sessionId, () -> searchKb(message));
        List<ConversationMessage> resolvedHistory = history;
        CompletableFuture<List<ReplySuggestionDTO>> contextFuture = runAsync(
                "LLM 推理", sessionId, () -> generateContextSuggestions(
                        sessionId,
                        message,
                        resolvedHistory != null ? resolvedHistory : historyRepository.findAll(sessionId)));

        List<ReplySuggestionDTO> merged = merge(kbFuture.join(), contextFuture.join());
        writeCache(cacheKey, merged, sessionId);
        return merged;
    }

    // ---- 内部方法 ----

    private CompletableFuture<List<ReplySuggestionDTO>> runAsync(String taskName,
                                                                 String sessionId,
                                                                 Supplier<List<ReplySuggestionDTO>> task) {
        try {
            return CompletableFuture.supplyAsync(task, suggestionExecutor)
                    .orTimeout(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(e -> {
                        log.warn("[Suggestion] {}失败/超时，降级为空列表 sessionId={}", taskName, sessionId, e);
                        return List.of();
                    });
        } catch (RejectedExecutionException e) {
            log.warn("[Suggestion] {}线程池已饱和，降级为空列表 sessionId={}", taskName, sessionId);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /**
     * 构建缓存 key：前缀 + sessionId + 消息内容 SHA-256（hex）。
     */
    private String buildCacheKey(String sessionId, String message) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return KEY_PREFIX + sessionId + ":" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 从对话历史取最后一条访客消息文本，供 lastMessage 未传时兜底使用。
     * 接受已加载的历史列表，不再重复查 Redis。
     */
    private String resolveLastUserMessage(List<ConversationMessage> history) {
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
     * 接受已加载的历史列表，不再重复查 Redis。
     * 解析 LLM 输出中以"数字点/顿号 空格"开头的行，confidence 固定 {@value #CONTEXT_CONFIDENCE}。
     */
    private List<ReplySuggestionDTO> generateContextSuggestions(String sessionId,
                                                                String lastMessage,
                                                                List<ConversationMessage> history) {
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
                    case "user" -> "访客：" + m.content();
                    case "assistant", "agent" -> "客服：" + m.content();
                    default -> "";
                })
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));

        String userPrompt = String.format(
                "%s\n\n访客最新消息：%s\n\n请生成回复建议：",
                contextBlock.isBlank() ? "（无历史记录）" : contextBlock,
                lastMessage);

        String response = modelFactory.chat(
                List.of(ChatMessage.user(userPrompt)),
                SUGGESTION_SYSTEM_PROMPT,
                Duration.ofSeconds(TASK_TIMEOUT_SECONDS));
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

    /**
     * 读取缓存，反序列化失败时静默降级。
     */
    private List<ReplySuggestionDTO> readCache(String cacheKey, String sessionId) {
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached == null) {
            return null;
        }
        try {
            List<ReplySuggestionDTO> result = JsonUtils.parseObject(
                    cached, new TypeReference<List<ReplySuggestionDTO>>() {
                    });
            if (result != null) {
                log.debug("[Suggestion] 命中缓存 sessionId={}", sessionId);
                return result;
            }
        } catch (Exception e) {
            log.warn("[Suggestion] 缓存反序列化失败，重新生成 sessionId={}", sessionId, e);
        }
        return null;
    }

    /**
     * 写入缓存，失败时静默降级不影响主流程。
     */
    private void writeCache(String cacheKey, List<ReplySuggestionDTO> data, String sessionId) {
        try {
            String json = JsonUtils.toJsonString(data);
            if (json != null) {
                redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(CACHE_TTL_SECONDS));
            }
        } catch (Exception e) {
            log.warn("[Suggestion] 缓存写入失败 sessionId={}", sessionId, e);
        }
    }
}
