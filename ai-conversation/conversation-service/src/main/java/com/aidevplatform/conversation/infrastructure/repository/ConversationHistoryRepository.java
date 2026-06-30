package com.aidevplatform.conversation.infrastructure.repository;

import com.aidevplatform.common.web.redis.RedisCacheHelper;
import com.aidevplatform.conversation.infrastructure.mq.ConversationMessagePublisher;
import lombok.extern.slf4j.Slf4j;
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
 * <p>Redis 操作统一通过 {@link RedisCacheHelper} 进行，不再直接使用 RedisTemplate。
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

    private final RedisCacheHelper             cache;
    private final ConversationMessagePublisher publisher;

    public ConversationHistoryRepository(RedisCacheHelper cache,
                                          ConversationMessagePublisher publisher) {
        this.cache     = cache;
        this.publisher = publisher;
    }

    /**
     * 追加访客或 AI 消息（role=user / assistant），并发布到 MQ 持久化。
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
     * Redis List 仍写 assistant 角色（保持 AI 历史格式兼容），DB 中标记为 agent。
     */
    public void appendAgentMessage(String sessionId, String content) {
        writeToListWithTrim(sessionId, ROLE_ASSISTANT, content);
        publishMessageEvent(sessionId, ROLE_AGENT, content);
    }

    /**
     * 获取全量历史消息列表（已截断至 MAX_HISTORY_TURNS）。
     * 供 AI 对话（ChatAppService）和座席接入时加载上下文使用。
     */
    public List<Map<String, String>> findAll(String sessionId) {
        String       key = KEY_PREFIX + sessionId;
        List<String> raw = cache.lRange(key, 0, -1);
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
     *
     * <p>通过 {@link RedisCacheHelper#replaceListAtomically} 在 Pipeline 中完成
     * DEL + RPUSH + EXPIRE，避免并发场景下 delete 与 rightPush 之间产生竞态。
     */
    public void saveAll(String sessionId, List<Map<String, String>> messages) {
        // 将每条 message 展开为 [role, content, role, content, ...] 序列写入 List
        List<String> elements = new ArrayList<>(messages.size() * 2);
        for (Map<String, String> msg : messages) {
            elements.add(msg.get("role"));
            elements.add(msg.get("content"));
        }
        cache.replaceListAtomically(KEY_PREFIX + sessionId, elements, Duration.ofHours(TTL_HOURS));

        // Pipeline 完成后再发布 MQ 事件（最后一条 assistant 消息）
        if (!messages.isEmpty()) {
            Map<String, String> last = messages.get(messages.size() - 1);
            if (ROLE_ASSISTANT.equals(last.get("role"))) {
                publishMessageEvent(sessionId, ROLE_ASSISTANT, last.get("content"));
            }
        }
    }

    /** 清除会话历史（会话结束或新建对话时调用） */
    public void delete(String sessionId) {
        cache.delete(KEY_PREFIX + sessionId);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /** 写入 Redis List 并 LTRIM 保留最新 MAX_HISTORY_TURNS 轮 */
    private void writeToListWithTrim(String sessionId, String role, String content) {
        String key = KEY_PREFIX + sessionId;
        cache.lRightPush(key, role);
        cache.lRightPush(key, content);
        cache.lTrim(key, -(MAX_HISTORY_TURNS * 2L), -1);
        cache.expire(key, Duration.ofHours(TTL_HOURS));
    }

    /**
     * 通过 RabbitMQ Publisher 发布消息事件（异步持久化）。
     * Publisher 内置 @Retryable（3次），三次失败后异常向上传播，打印 WARN 日志。
     */
    private void publishMessageEvent(String sessionId, String role, String content) {
        try {
            publisher.publishMessage(sessionId, role, content);
        } catch (Exception e) {
            log.warn("[History] MQ 发布失败（3次重试后），消息仅存 Redis List，sessionId={}", sessionId, e);
        }
    }
}
