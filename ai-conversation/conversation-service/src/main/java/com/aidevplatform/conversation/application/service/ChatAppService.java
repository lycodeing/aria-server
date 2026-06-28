package com.aidevplatform.conversation.application.service;

import com.aidevplatform.conversation.infrastructure.ai.CtyunAiClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

/**
 * 对话应用服务。
 * 管理多轮对话历史（Redis），调用 AI 生成回复。
 */
@Service
public class ChatAppService {

    private static final String SESSION_KEY_PREFIX = "chat:session:";
    private static final int MAX_HISTORY = 20;         // 最多保留20轮历史
    private static final long SESSION_TTL_HOURS = 24;

    private static final String SYSTEM_PROMPT = """
            你是一名专业的智能客服助手。
            请用简洁、友好的语言回答用户问题。
            如果涉及订单、退款等敏感操作，引导用户验证身份。
            回答要简明扼要，避免冗长说明。
            """;

    private final CtyunAiClient aiClient;
    private final StringRedisTemplate redis;

    public ChatAppService(CtyunAiClient aiClient, StringRedisTemplate redis) {
        this.aiClient = aiClient;
        this.redis = redis;
    }

    /**
     * 流式对话：返回 SSE 文本 chunk Flux。
     * 自动维护 Redis 中的多轮历史。
     *
     * @param sessionId 会话 ID（访客或已登录用户）
     * @param userMessage 用户消息
     */
    public Flux<String> streamChat(String sessionId, String userMessage) {
        // 1. 拉取历史消息
        List<Map<String, String>> history = getHistory(sessionId);

        // 2. 追加用户消息
        history.add(Map.of("role", "user", "content", userMessage));

        // 3. 调用 AI 获取流式回复
        StringBuilder assistantReply = new StringBuilder();

        return aiClient.streamChat(history, SYSTEM_PROMPT)
                .map(chunk -> {
                    String content = aiClient.extractDeltaContent(chunk);
                    if (!content.isEmpty()) {
                        assistantReply.append(content);
                    }
                    return content;
                })
                .filter(content -> !content.isEmpty())
                .doOnComplete(() -> {
                    // 4. 流结束后保存完整历史
                    if (!assistantReply.isEmpty()) {
                        history.add(Map.of("role", "assistant", "content", assistantReply.toString()));
                        saveHistory(sessionId, history);
                    }
                })
                .onErrorResume(e -> {
                    String errMsg = "抱歉，AI 服务暂时不可用，请稍后重试。";
                    return Flux.just(errMsg);
                });
    }

    /**
     * 非流式对话（用于单次问答场景）。
     */
    public String chat(String sessionId, String userMessage) {
        List<Map<String, String>> history = getHistory(sessionId);
        history.add(Map.of("role", "user", "content", userMessage));

        String reply = aiClient.chat(history, SYSTEM_PROMPT);

        history.add(Map.of("role", "assistant", "content", reply));
        saveHistory(sessionId, history);

        return reply;
    }

    /**
     * 清除会话历史。
     */
    public void clearHistory(String sessionId) {
        redis.delete(SESSION_KEY_PREFIX + sessionId);
    }

    /**
     * 获取会话历史消息列表（用于前端展示）。
     */
    public List<Map<String, String>> getHistory(String sessionId) {
        String key = SESSION_KEY_PREFIX + sessionId;
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) return new ArrayList<>();

        List<Map<String, String>> messages = new ArrayList<>();
        for (int i = 0; i < raw.size() - 1; i += 2) {
            String role = raw.get(i);
            String content = raw.get(i + 1);
            messages.add(Map.of("role", role, "content", content));
        }
        // 超过最大历史轮数时截断（保留最新的 MAX_HISTORY 条）
        if (messages.size() > MAX_HISTORY) {
            messages = messages.subList(messages.size() - MAX_HISTORY, messages.size());
        }
        return new ArrayList<>(messages);
    }

    // -------------------------------------------------------
    // 内部工具：Redis 存储格式为 [role, content, role, content, ...]
    // -------------------------------------------------------

    private void saveHistory(String sessionId, List<Map<String, String>> messages) {
        String key = SESSION_KEY_PREFIX + sessionId;
        redis.delete(key);
        for (Map<String, String> msg : messages) {
            redis.opsForList().rightPush(key, msg.get("role"));
            redis.opsForList().rightPush(key, msg.get("content"));
        }
        redis.expire(key, Duration.ofHours(SESSION_TTL_HOURS));
    }
}
