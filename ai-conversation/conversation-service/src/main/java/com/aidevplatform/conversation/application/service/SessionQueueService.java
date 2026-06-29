package com.aidevplatform.conversation.application.service;

import com.aidevplatform.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aidevplatform.conversation.domain.SessionEventType;
import com.aidevplatform.conversation.domain.SessionStatus;
import com.aidevplatform.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aidevplatform.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * 会话队列服务。
 *
 * <p>职责：
 * <ol>
 *   <li>维护 Redis Hash {@code agent:session:queue}，存储会话元数据</li>
 *   <li>通过 RabbitMQ Fanout {@code cs.conversation.events} 实时通知座席端 SSE</li>
 *   <li>通过 RabbitMQ Direct {@code cs.conversation} 发布生命周期事件，
 *       供 ConversationMessageConsumer 异步消费并持久化到 PostgreSQL</li>
 * </ol>
 *
 * <p>状态机（由 {@link SessionStatus} 枚举保证合法转换）：
 * <pre>WAITING → ACTIVE → CLOSED</pre>
 *
 * <p>Redis 数据结构：
 * <ul>
 *   <li>Hash  {@code agent:session:queue} → {sessionId: JSON(SessionQueueItem)}</li>
 * </ul>
 *
 * <p>RabbitMQ 拓扑：
 * <ul>
 *   <li>Fanout {@code cs.conversation.events} → 实时事件广播给座席 SSE</li>
 *   <li>Direct {@code cs.conversation}        → 持久化消息（会话生命周期 + 对话消息）</li>
 * </ul>
 */
@Slf4j
@Service
public class SessionQueueService {

    // ---- Redis key 常量 ----
    private static final String QUEUE_KEY = "agent:session:queue";

    private final StringRedisTemplate redis;
    /** 注入 Spring 管理的 ObjectMapper，确保继承 Boot 自动配置（record 支持、null 处理等） */
    private final ObjectMapper objectMapper;
    /** 用于向持久化 Direct Exchange 发布会话生命周期事件 */
    private final ConversationMessagePublisher publisher;
    /** 用于向事件 Fanout Exchange 广播队列变更事件（实时推送给座席 SSE） */
    private final RabbitTemplate rabbitTemplate;
    /** 事件广播 Fanout Exchange 名称 */
    private final String eventsExchange;
    /**
     * DB 持久化 Repository，作为 ACTIVE 会话的 source of truth。
     * Redis Hash 丢失后（重启/驱逐），从 DB 恢复，保证数据不丢。
     */
    private final ConversationPersistRepository persistRepository;

    public SessionQueueService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            ConversationMessagePublisher publisher,
            @Qualifier("eventsRabbitTemplate") RabbitTemplate rabbitTemplate,
            @Value("${conversation.events.exchange}") String eventsExchange,
            ConversationPersistRepository persistRepository) {
        this.redis              = redis;
        this.objectMapper       = objectMapper;
        this.publisher          = publisher;
        this.rabbitTemplate     = rabbitTemplate;
        this.eventsExchange     = eventsExchange;
        this.persistRepository  = persistRepository;
    }

    // ---- 队列操作 ----

    /**
     * 用户请求转人工，加入等待队列，广播 Fanout 事件，
     * 并向持久化 Direct Exchange 发布 SESSION_START 事件。
     *
     * @param sessionId      会话唯一标识
     * @param userName       访客名称
     * @param transferReason 转接原因
     * @param tag            问题分类标签
     * @return 创建的会话队列项
     */
    public SessionQueueItem enqueue(String sessionId, String userName,
                                    String transferReason, String tag) {
        SessionQueueItem item = new SessionQueueItem(
                sessionId, userName, transferReason, tag,
                Instant.now().getEpochSecond(), SessionStatus.WAITING
        );
        try {
            redis.opsForHash().put(QUEUE_KEY, sessionId, objectMapper.writeValueAsString(item));
            // 实时通知座席（RabbitMQ Fanout → SSE 广播）
            publishEvent(new SessionEvent(SessionEventType.ENQUEUE, item));
            // 持久化队列（RabbitMQ Direct，异步写入 PostgreSQL）
            publishSessionStart(sessionId, userName, transferReason, tag, item.waitSince());
            log.info("[SessionQueue] enqueue sessionId={} userName={}", sessionId, userName);
        } catch (Exception e) {
            log.error("[SessionQueue] enqueue error sessionId={}", sessionId, e);
        }
        return item;
    }

    /**
     * 查询等待队列（所有 WAITING 状态）。
     *
     * @return 按入队时间升序排列的等待列表
     */
    public List<SessionQueueItem> getQueue() {
        return getByStatus(SessionStatus.WAITING);
    }

    /**
     * 查询进行中的会话（ACTIVE），刷新后恢复座席界面使用。
     *
     * <p>从 DB 读取（source of truth），不依赖 Redis。
     * Redis 重启/驱逐后仍能正确返回数据。
     *
     * @return 进行中的会话列表，按入队时间升序
     */
    public List<SessionQueueItem> getActiveSessions() {
        return persistRepository.getActiveConversations().stream()
                .map(e -> new SessionQueueItem(
                        e.getSessionId(),
                        e.getVisitorName(),
                        e.getTransferReason(),
                        e.getTag(),
                        e.getStartedAt() != null ? e.getStartedAt().toEpochSecond() : 0L,
                        SessionStatus.ACTIVE))
                .toList();
    }

    private List<SessionQueueItem> getByStatus(SessionStatus status) {
        Map<Object, Object> all = redis.opsForHash().entries(QUEUE_KEY);
        List<SessionQueueItem> result = new ArrayList<>(all.size());
        for (Object val : all.values()) {
            try {
                SessionQueueItem item = objectMapper.readValue((String) val, SessionQueueItem.class);
                if (status == item.status()) {
                    result.add(item);
                }
            } catch (Exception e) {
                log.warn("[SessionQueue] deserialize failed, val={}", val, e);
            }
        }
        result.sort(Comparator.comparingLong(SessionQueueItem::waitSince));
        return result;
    }

    /**
     * 座席接入会话，状态 WAITING → ACTIVE。
     * 状态转换由 {@link SessionStatus#transitionTo} 校验合法性。
     *
     * @param sessionId 会话唯一标识
     * @return 更新后的会话队列项
     * @throws RuntimeException 会话不存在或状态非法时抛出
     */
    public SessionQueueItem accept(String sessionId) {
        try {
            Object raw = redis.opsForHash().get(QUEUE_KEY, sessionId);
            if (raw == null) {
                throw new IllegalArgumentException("Session not found: " + sessionId);
            }
            SessionQueueItem old = objectMapper.readValue((String) raw, SessionQueueItem.class);
            SessionStatus newStatus = old.status().transitionTo(SessionStatus.ACTIVE);
            SessionQueueItem updated = new SessionQueueItem(
                    old.sessionId(), old.userName(), old.transferReason(),
                    old.tag(), old.waitSince(), newStatus
            );
            redis.opsForHash().put(QUEUE_KEY, sessionId, objectMapper.writeValueAsString(updated));
            // 实时广播（Fanout SSE）
            publishEvent(new SessionEvent(SessionEventType.ACCEPTED, updated));
            // 持久化：DB 状态 WAITING → ACTIVE（source of truth）
            publishSessionAccept(sessionId, Instant.now().getEpochSecond());
            return updated;
        } catch (Exception e) {
            log.error("[SessionQueue] accept error sessionId={}", sessionId, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 结束或转交会话，从队列中移除，并向持久化 Direct Exchange 发布 SESSION_END 事件。
     *
     * <p>关键修复：DB 关闭操作（publishSessionEnd）与 Redis 状态解耦。
     * Redis 重启/驱逐后 Hash 可能为空，但 DB 中记录仍为 ACTIVE，
     * 必须确保无论 Redis 是否有数据都能正确关闭 DB 状态。
     *
     * @param sessionId 会话唯一标识
     */
    public void close(String sessionId) {
        try {
            Object raw = redis.opsForHash().get(QUEUE_KEY, sessionId);
            if (raw != null) {
                // Redis 有数据：走完整流程（Fanout 广播 + 删 Redis + MQ 持久化）
                SessionQueueItem old = objectMapper.readValue((String) raw, SessionQueueItem.class);
                SessionStatus newStatus = old.status().transitionTo(SessionStatus.CLOSED);
                SessionQueueItem closed = new SessionQueueItem(
                        old.sessionId(), old.userName(), old.transferReason(),
                        old.tag(), old.waitSince(), newStatus
                );
                publishEvent(new SessionEvent(SessionEventType.CLOSED, closed));
                redis.opsForHash().delete(QUEUE_KEY, sessionId);
            } else {
                // Redis 已无数据（重启/驱逐）：仅广播最小事件，Redis HDEL 幂等无害
                log.warn("[SessionQueue] close 时 Redis 无数据（可能已重启），仍执行 DB 关闭 sessionId={}", sessionId);
                SessionQueueItem minimal = new SessionQueueItem(sessionId, "", "", "", 0L, SessionStatus.CLOSED);
                publishEvent(new SessionEvent(SessionEventType.CLOSED, minimal));
                redis.opsForHash().delete(QUEUE_KEY, sessionId); // 幂等，无数据时 no-op
            }
            // 无论 Redis 有无数据，始终发布 MQ 事件确保 DB 状态更新为 CLOSED
            publishSessionEnd(sessionId);
        } catch (Exception e) {
            log.error("[SessionQueue] close error sessionId={}", sessionId, e);
        }
    }

    /**
     * 检查会话是否已被座席接入（供 ChatController 判断是否还走 AI）。
     *
     * <p>优先查 Redis（快），Redis 缺失时兜底查 DB（防止 Redis 重启后误判为未接入，
     * 导致已转人工的会话被 AI 错误接管）。
     *
     * @param sessionId 会话唯一标识
     * @return true 表示已接入（ACTIVE）
     */
    public boolean isActive(String sessionId) {
        Object raw = redis.opsForHash().get(QUEUE_KEY, sessionId);
        if (raw != null) {
            try {
                SessionQueueItem item = objectMapper.readValue((String) raw, SessionQueueItem.class);
                return SessionStatus.ACTIVE == item.status();
            } catch (Exception e) {
                log.warn("[SessionQueue] isActive deserialize error sessionId={}", sessionId, e);
            }
        }
        // Redis 缺失：兜底查 DB，防止 Redis 重启后误判
        boolean dbActive = persistRepository.isActiveInDb(sessionId);
        if (dbActive) {
            log.debug("[SessionQueue] Redis 缺失，DB 兜底确认 ACTIVE sessionId={}", sessionId);
        }
        return dbActive;
    }

    // ---- 内部：事件广播 ----

    /**
     * 向 {@code cs.conversation.events} Fanout Exchange 发布队列状态变更事件。
     * 由 {@link com.aidevplatform.conversation.infrastructure.mq.SessionEventSubscriber}
     * 消费后广播给所有活跃 SSE 连接。
     *
     * @param event 队列事件
     */
    private void publishEvent(SessionEvent event) {
        try {
            // routingKey 对 fanout exchange 无效，传 "" 即可
            rabbitTemplate.convertAndSend(eventsExchange, "", event);
        } catch (Exception e) {
            log.error("[SessionQueue] Fanout 事件发布失败", e);
        }
    }

    /**
     * 发布会话开始事件到持久化 RabbitMQ Direct Exchange。
     * Publisher 内置 @Retryable（3次），失败后实时链路（Redis Hash / Fanout）不受影响。
     */
    private void publishSessionStart(String sessionId, String visitorName,
                                     String transferReason, String tag, long timestamp) {
        try {
            publisher.publishSessionStart(sessionId, visitorName, transferReason, tag, timestamp);
        } catch (Exception e) {
            log.warn("[SessionQueue] SESSION_START MQ 发布失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 发布座席接入事件到持久化 RabbitMQ Direct Exchange。
     * Consumer 消费后将 DB 状态从 WAITING 更新为 ACTIVE。
     */
    private void publishSessionAccept(String sessionId, long timestamp) {
        try {
            publisher.publishSessionAccept(sessionId, timestamp);
        } catch (Exception e) {
            log.warn("[SessionQueue] SESSION_ACCEPT MQ 发布失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 发布会话结束事件到持久化 RabbitMQ Direct Exchange。
     */
    private void publishSessionEnd(String sessionId) {
        try {
            publisher.publishSessionEnd(sessionId);
        } catch (Exception e) {
            log.warn("[SessionQueue] SESSION_END MQ 发布失败 sessionId={}", sessionId, e);
        }
    }

    // ---- VO ----

    public record SessionQueueItem(
            String sessionId,
            String userName,
            String transferReason,
            String tag,
            long waitSince,
            SessionStatus status
    ) {}

    public record SessionEvent(SessionEventType type, SessionQueueItem item) {}
}
