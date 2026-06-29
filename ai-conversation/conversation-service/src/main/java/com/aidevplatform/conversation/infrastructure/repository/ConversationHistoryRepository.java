package com.aidevplatform.conversation.infrastructure.repository;

import com.aidevplatform.conversation.infrastructure.mq.ConversationMessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话历史 Repository。
 *
 * <p>职责一：维护 Redis List 热数据（key: {@code chat:session:{id}}），供 AI 对话和 WS 路由实时读取。
 * <p>职责二：每次追加消息时，通过 {@link ConversationMessagePublisher} 发布到 RabbitMQ Direct Exchange，
 * 由 {@link com.aidevplatform.conversation.infrastructure.mq.ConversationMessageConsumer}
 * 异步消费并持久化到 PostgreSQL。
 *
 * <p>双写策略：
 * <pre>
 *   append() / appendAgentMessage()
 *     ├─ Redis List  chat:session:{id}   （热数据，实时路由，TTL 24h）
 *     └─ RabbitMQ MESSAGE 事件           （冷存储，异步持久化 → DB）
 * </pre>
 *
 * <p>角色约定：
 * <ul>
 *   <li>Redis List / AI 请求：user / assistant（OpenAI 标准，兼容 AI 回退）</li>
 *   <li>Stream → DB：user / assistant（AI）/ agent（人工座席，便于质检分析）</li>
 * </ul>
 *
 * <p>Stream 写入失败时仅打印警告，不影响 Redis List 的写入（实时功能优先）。
 */
@Slf4j
@Repository
public class ConversationHistoryRepository {

    /** Redis List key 前缀（热数据，实时路由用） */
    private static final String KEY_PREFIX        = "chat:session:";
    /** 热数据 TTL（小时） */
    private static final long   TTL_HOURS         = 24L;
    /** 每个会话最多保留的对话轮数 */
    private static final int    MAX_HISTORY_TURNS = 20;

    /** AI 兼容角色标识（Redis List 和 AI 请求使用） */
    private static final String ROLE_ASSISTANT = "assistant";
    /** DB 持久化时人工座席角色标识（DB 专用，区分 AI 和人工） */
    private static final String ROLE_AGENT     = "agent";

    private final StringRedisTemplate redis;
    private final ConversationMessagePublisher publisher;

    /**
     * Redis Stream key，从 yml {@code conversation.persist.stream-key} 注入。
     * 与 ConversationStreamWorker 消费端保持同一配置来源，消除硬编码分歧风险。
     */

    public ConversationHistoryRepository(
            StringRedisTemplate redis,
            ConversationMessagePublisher publisher) {
        this.redis        = redis;
        this.publisher = publisher;
    }

    /**
     * 追加访客或 AI 消息（role=user / assistant），并发布到 Redis Stream 持久化。
     *
     * <p>人工座席消息请使用 {@link #appendAgentMessage(String, String)}，
     * 两者在 DB 中的 role 字段不同。
     *
     * @param sessionId 会话 ID
     * @param role      消息角色（user / assistant）
     * @param content   消息内容
     */
    public void append(String sessionId, String role, String content) {
        writeToListWithTrim(sessionId, role, content);
        publishMessageEvent(sessionId, role, content);
    }

    /**
     * 追加人工座席消息。
     *
     * <p>与 {@link #append} 的区别：
     * <ul>
     *   <li>Redis List 写入 {@code "assistant"}（保持 AI 历史格式兼容，
     *       万一会话回落 AI 时不含非标角色）</li>
     *   <li>Stream → DB 写入 {@code "agent"}（区分 AI 和人工，支持质检分析）</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @param content   座席回复内容
     */
    public void appendAgentMessage(String sessionId, String content) {
        writeToListWithTrim(sessionId, ROLE_ASSISTANT, content);
        publishMessageEvent(sessionId, ROLE_AGENT, content);
    }

    /**
     * 获取全量历史消息列表（已截断至 MAX_HISTORY_TURNS）。
     * 供 AI 对话（ChatAppService）和座席接入时加载上下文使用。
     *
     * @param sessionId 会话 ID
     * @return 消息列表，格式：[{"role":"user","content":"..."}, ...]
     */
    public List<Map<String, String>> findAll(String sessionId) {
        String       key = KEY_PREFIX + sessionId;
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, String>> messages = new ArrayList<>(raw.size() / 2);
        for (int i = 0; i < raw.size() - 1; i += 2) {
            messages.add(Map.of("role", raw.get(i), "content", raw.get(i + 1)));
        }

        if (messages.size() > MAX_HISTORY_TURNS) {
            messages = messages.subList(messages.size() - MAX_HISTORY_TURNS, messages.size());
        }
        return new ArrayList<>(messages);
    }

    /**
     * 全量覆盖保存历史（AI 流式回复完成后调用）。
     * 仅将最后一条 assistant 消息发布到 Stream，避免重复发布 user 消息。
     *
     * @param sessionId 会话 ID
     * @param messages  消息列表（已截断至 MAX_HISTORY_TURNS）
     */
    public void saveAll(String sessionId, List<Map<String, String>> messages) {
        String key = KEY_PREFIX + sessionId;
        redis.delete(key);
        for (Map<String, String> msg : messages) {
            redis.opsForList().rightPush(key, msg.get("role"));
            redis.opsForList().rightPush(key, msg.get("content"));
        }
        redis.expire(key, Duration.ofHours(TTL_HOURS));

        if (!messages.isEmpty()) {
            Map<String, String> last = messages.get(messages.size() - 1);
            if (ROLE_ASSISTANT.equals(last.get("role"))) {
                publishMessageEvent(sessionId, ROLE_ASSISTANT, last.get("content"));
            }
        }
    }

    /**
     * 清除会话历史（会话结束或新建对话时调用）。
     *
     * @param sessionId 会话 ID
     */
    public void delete(String sessionId) {
        redis.delete(KEY_PREFIX + sessionId);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /** 写入 Redis List 并 LTRIM 保留最新 MAX_HISTORY_TURNS 轮 */
    private void writeToListWithTrim(String sessionId, String role, String content) {
        String key = KEY_PREFIX + sessionId;
        redis.opsForList().rightPush(key, role);
        redis.opsForList().rightPush(key, content);
        redis.opsForList().trim(key, -(MAX_HISTORY_TURNS * 2L), -1);
        redis.expire(key, Duration.ofHours(TTL_HOURS));
    }

    /**
     * 通过 RabbitMQ Publisher 发布消息事件（异步持久化）。
     * Publisher 内置 @Retryable（3次），三次失败后异常向上传播，打印 WARN 日志。
     *
     * @param sessionId 会话 ID
     * @param role      存入 DB 的角色标识（user / assistant / agent）
     * @param content   消息内容
     */
    private void publishMessageEvent(String sessionId, String role, String content) {
        try {
            publisher.publishMessage(sessionId, role, content);
        } catch (Exception e) {
            log.warn("[History] MQ 发布失败（3次重试后），消息仅存 Redis List，sessionId={}", sessionId, e);
        }
    }
}
