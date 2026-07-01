package com.aria.conversation.application.service;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.common.web.redis.RedisLockHelper;
import com.aria.conversation.application.exception.SessionEnqueueException;
import com.aria.conversation.domain.SessionAlreadyAcceptedException;
import com.aria.conversation.domain.SessionEventType;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 会话队列服务。
 *
 * <p>职责：
 * <ol>
 *   <li>维护 Redis Hash {@code agent:session:queue}，存储会话元数据（通过 {@link RedisCacheHelper}）</li>
 *   <li>通过 RabbitMQ Fanout {@code cs.conversation.events} 实时通知座席端 SSE</li>
 *   <li>通过 RabbitMQ Direct {@code cs.conversation} 发布生命周期事件，
 *       供 ConversationMessageConsumer 异步消费并持久化到 PostgreSQL</li>
 * </ol>
 *
 * <p>Redis 操作严格通过工具类隔离（职责分离原则）：
 * <ul>
 *   <li>{@link RedisCacheHelper} — Hash 缓存读写</li>
 *   <li>{@link RedisLockHelper}  — accept/transfer 的 Hash CAS 原子操作</li>
 * </ul>
 *
 * <p>状态机（由 {@link SessionStatus} 枚举保证合法转换）：
 * <pre>WAITING → ACTIVE → CLOSED</pre>
 */
@Slf4j
@Service
public class SessionQueueService {

    // ---- Redis key 常量 ----
    private static final String QUEUE_KEY         = "agent:session:queue";
    /** 在线座席注册表，Hash：{agentId → JSON{name,connectedAt}} */
    private static final String ONLINE_AGENTS_KEY = "agent:online";

    /** 会话队列 Hash 的 TTL（每次写入刷新，避免脏数据永久驻留） */
    private static final Duration QUEUE_HASH_TTL  = Duration.ofDays(1);
    /** 在线座席 Hash 的 TTL（每次注册/续期刷新） */
    private static final Duration ONLINE_HASH_TTL = Duration.ofHours(12);

    /** CAS 期望状态标记：accept 时校验当前为 WAITING */
    private static final String MARKER_STATUS_WAITING = "\"status\":\"WAITING\"";
    /** CAS 期望源座席标记模板：transfer 时校验当前 agentId 匹配（plain-mode 字符串拼接） */
    private static final String MARKER_AGENT_ID_TPL   = "\"agentId\":\"%s\"";
    /** agentId 合法字符集：与 SessionQueueController 校验一致，防止 JSON 注入破坏 CAS 标记 */
    private static final Pattern AGENT_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    private final RedisCacheHelper             cache;
    private final RedisLockHelper              lockHelper;
    /** 注入 Spring 管理的 ObjectMapper，确保继承 Boot 自动配置（record 支持、null 处理等） */
    private final ObjectMapper                 objectMapper;
    /** 用于向持久化 Direct Exchange 发布会话生命周期事件 */
    private final ConversationMessagePublisher publisher;
    /** 用于向事件 Fanout Exchange 广播队列变更事件（实时推送给座席 SSE） */
    private final RabbitTemplate               rabbitTemplate;
    /** 事件广播 Fanout Exchange 名称 */
    private final String                       eventsExchange;
    /** DB 持久化 Repository，作为 ACTIVE 会话的 source of truth */
    private final ConversationPersistRepository persistRepository;

    public SessionQueueService(
            RedisCacheHelper cache,
            RedisLockHelper lockHelper,
            ObjectMapper objectMapper,
            ConversationMessagePublisher publisher,
            @Qualifier("eventsRabbitTemplate") RabbitTemplate rabbitTemplate,
            @Value("${conversation.events.exchange}") String eventsExchange,
            ConversationPersistRepository persistRepository) {
        this.cache              = cache;
        this.lockHelper         = lockHelper;
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
     */
    public SessionQueueItem enqueue(String sessionId, String userName,
                                    String transferReason, String tag) {
        SessionQueueItem item = new SessionQueueItem(
                sessionId, userName, transferReason, tag,
                Instant.now().getEpochSecond(), SessionStatus.WAITING, null
        );
        try {
            // 使用带 TTL 的 hPut，防止 Hash 永不过期（会话异常未 close 时由 TTL 兜底清理）
            cache.hPut(QUEUE_KEY, sessionId, objectMapper.writeValueAsString(item), QUEUE_HASH_TTL);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[SessionQueue] enqueue 序列化失败 sessionId={}", sessionId, e);
            throw new SessionEnqueueException("会话入队失败，请稍后重试", sessionId, e);
        }
        publishEvent(new SessionEvent(SessionEventType.ENQUEUE, item));
        publishSessionStart(sessionId, userName, transferReason, tag, item.waitSince());
        log.info("[SessionQueue] enqueue sessionId={} userName={}", sessionId, userName);
        return item;
    }

    /** 查询等待队列（所有 WAITING 状态） */
    public List<SessionQueueItem> getQueue() {
        return getByStatus(SessionStatus.WAITING);
    }

    /**
     * 查询进行中的会话（ACTIVE），刷新后恢复座席界面使用。
     * 从 DB 读取（source of truth），不依赖 Redis。
     */
    public List<SessionQueueItem> getActiveSessions() {
        return persistRepository.getActiveConversations().stream()
                .map(e -> new SessionQueueItem(
                        e.getSessionId(),
                        e.getVisitorName(),
                        e.getTransferReason(),
                        e.getTag(),
                        e.getStartedAt() != null ? e.getStartedAt().toEpochSecond() : 0L,
                        SessionStatus.ACTIVE,
                        null))
                .toList();
    }

    private List<SessionQueueItem> getByStatus(SessionStatus status) {
        Map<Object, Object> all = cache.hEntries(QUEUE_KEY);
        List<SessionQueueItem> result = new ArrayList<>(all.size());
        for (Object val : all.values()) {
            try {
                SessionQueueItem item = objectMapper.readValue((String) val, SessionQueueItem.class);
                if (status == item.status()) {
                    result.add(item);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("[SessionQueue] deserialize failed, val={}", val, e);
            }
        }
        result.sort(Comparator.comparingLong(SessionQueueItem::waitSince));
        return result;
    }

    /**
     * 座席接入会话，状态 WAITING → ACTIVE。
     *
     * <p>使用 {@link RedisLockHelper#compareAndSetHashField} 保证 CAS 原子性，
     * 防止两名座席并发接入同一会话（TOCTOU 竞态）。
     */
    public SessionQueueItem accept(String sessionId, String agentId) {
        try {
            String rawJson = cache.hGet(QUEUE_KEY, sessionId);
            if (rawJson == null) {
                throw new IllegalArgumentException("会话不存在: " + sessionId);
            }
            SessionQueueItem old = objectMapper.readValue(rawJson, SessionQueueItem.class);
            SessionStatus newStatus = old.status().transitionTo(SessionStatus.ACTIVE);
            SessionQueueItem updated = new SessionQueueItem(
                    old.sessionId(), old.userName(), old.transferReason(),
                    old.tag(), old.waitSince(), newStatus, agentId
            );
            String updatedJson = objectMapper.writeValueAsString(updated);

            boolean ok = lockHelper.compareAndSetHashField(
                    QUEUE_KEY, sessionId, MARKER_STATUS_WAITING, updatedJson);
            if (!ok) {
                throw new SessionAlreadyAcceptedException(sessionId);
            }
            // CAS 成功后刷新 Hash 整体 TTL，避免长会话过期被驱逐
            cache.expire(QUEUE_KEY, QUEUE_HASH_TTL);

            publishEvent(new SessionEvent(SessionEventType.ACCEPTED, updated));
            publishSessionAccept(sessionId, agentId, Instant.now().getEpochSecond());
            log.info("[SessionQueue] accept 成功 sessionId={}", sessionId);
            return updated;
        } catch (SessionAlreadyAcceptedException | IllegalArgumentException e) {
            throw e;
        } catch (IllegalStateException e) {
            // 非 WAITING 状态时 transitionTo 抛出 IllegalStateException，统一翻译为 409
            throw new SessionAlreadyAcceptedException(sessionId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[SessionQueue] accept 序列化失败 sessionId={}", sessionId, e);
            throw new RuntimeException("会话数据损坏: " + sessionId, e);
        }
    }

    /**
     * 结束或转交会话，从队列中移除，并向持久化 Direct Exchange 发布 SESSION_END 事件。
     *
     * <p>DB 关闭操作（publishSessionEnd）与 Redis 状态解耦：无论 Redis 有无数据都执行 DB 关闭。
     */
    public void close(String sessionId) {
        try {
            String rawJson = cache.hGet(QUEUE_KEY, sessionId);
            if (rawJson != null) {
                SessionQueueItem old = objectMapper.readValue(rawJson, SessionQueueItem.class);
                SessionStatus newStatus = old.status().transitionTo(SessionStatus.CLOSED);
                SessionQueueItem closed = new SessionQueueItem(
                        old.sessionId(), old.userName(), old.transferReason(),
                        old.tag(), old.waitSince(), newStatus, old.agentId()
                );
                publishEvent(new SessionEvent(SessionEventType.CLOSED, closed));
                cache.hDelete(QUEUE_KEY, sessionId);
            } else {
                log.warn("[SessionQueue] close 时 Redis 无数据（可能已重启），仍执行 DB 关闭 sessionId={}", sessionId);
                SessionQueueItem minimal = new SessionQueueItem(sessionId, "", "", "", 0L, SessionStatus.CLOSED, null);
                publishEvent(new SessionEvent(SessionEventType.CLOSED, minimal));
                cache.hDelete(QUEUE_KEY, sessionId); // 幂等，无数据时 no-op
            }
            publishSessionEnd(sessionId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[SessionQueue] close 序列化失败 sessionId={}", sessionId, e);
        } catch (IllegalStateException e) {
            // transitionTo 检测到非法状态转换（如 CLOSED→CLOSED），属预期失败
            log.warn("[SessionQueue] close 状态机校验失败 sessionId={} msg={}", sessionId, e.getMessage());
        }
    }

    /**
     * 检查会话是否已被座席接入（供 ChatController 判断是否还走 AI）。
     *
     * <p>优先查 Redis（快），Redis 缺失时兜底查 DB。
     */
    public boolean isActive(String sessionId) {
        String rawJson = cache.hGet(QUEUE_KEY, sessionId);
        if (rawJson != null) {
            try {
                SessionQueueItem item = objectMapper.readValue(rawJson, SessionQueueItem.class);
                return SessionStatus.ACTIVE == item.status();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("[SessionQueue] isActive deserialize error sessionId={}", sessionId, e);
            }
        }
        boolean dbActive = persistRepository.isActiveInDb(sessionId);
        if (dbActive) {
            log.debug("[SessionQueue] Redis 缺失，DB 兜底确认 ACTIVE sessionId={}", sessionId);
        }
        return dbActive;
    }

    /**
     * 转交会话给指定座席（当前座席 → 目标座席，状态保持 ACTIVE）。
     *
     * <p>使用 {@link RedisLockHelper#compareAndSetHashField} 校验当前 agentId 后原子更新。
     */
    public void transfer(String sessionId, String fromAgentId, String targetAgentId) {
        // 二次防护：fromAgentId 即将拼入 CAS 标记字符串，若含特殊字符（"、\、控制字符）
        // 会破坏 Jackson 输出的 JSON 转义形式，导致 CAS 永远失败（DoS）或匹配错误字段值。
        // Controller 层已校验，此处再做兜底防御。
        if (fromAgentId == null || !AGENT_ID_PATTERN.matcher(fromAgentId).matches()) {
            throw new IllegalArgumentException("fromAgentId 格式非法: " + fromAgentId);
        }
        if (targetAgentId == null || !AGENT_ID_PATTERN.matcher(targetAgentId).matches()) {
            throw new IllegalArgumentException("targetAgentId 格式非法: " + targetAgentId);
        }
        if (!cache.hHasKey(ONLINE_AGENTS_KEY, targetAgentId)) {
            throw new IllegalArgumentException("目标座席不在线: " + targetAgentId);
        }
        try {
            String rawJson = cache.hGet(QUEUE_KEY, sessionId);
            if (rawJson == null) {
                throw new IllegalArgumentException("会话不存在: " + sessionId);
            }
            SessionQueueItem old = objectMapper.readValue(rawJson, SessionQueueItem.class);
            if (old.status() != SessionStatus.ACTIVE) {
                throw new IllegalStateException("只有 ACTIVE 状态的会话才能转交，当前状态: " + old.status());
            }
            SessionQueueItem transferred = new SessionQueueItem(
                    old.sessionId(), old.userName(), old.transferReason(),
                    old.tag(), old.waitSince(), SessionStatus.ACTIVE, targetAgentId
            );
            String updatedJson = objectMapper.writeValueAsString(transferred);
            String expectedMarker = String.format(MARKER_AGENT_ID_TPL, fromAgentId);

            boolean ok = lockHelper.compareAndSetHashField(
                    QUEUE_KEY, sessionId, expectedMarker, updatedJson);
            if (!ok) {
                throw new IllegalStateException("会话归属已变更，无法转交: " + sessionId);
            }
            // CAS 成功后刷新 Hash 整体 TTL
            cache.expire(QUEUE_KEY, QUEUE_HASH_TTL);

            publishEvent(new SessionEvent(
                    SessionEventType.TRANSFER, transferred, fromAgentId, targetAgentId));
            publishSessionTransfer(sessionId, fromAgentId, targetAgentId,
                    Instant.now().getEpochSecond());
            log.info("[SessionQueue] 会话转交 sessionId={} {} → {}",
                    sessionId, fromAgentId, targetAgentId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[SessionQueue] transfer 序列化失败 sessionId={}", sessionId, e);
            throw new RuntimeException("会话数据损坏: " + sessionId, e);
        }
    }

    // ---- 在线座席注册表 ----

    /**
     * 注册座席上线（SSE 连接建立时调用）。
     * 使用带 TTL 的 hPut，座席异常断连不再调用 deregister 时由 TTL 兜底清理。
     */
    public void registerAgent(String agentId, String displayName) {
        try {
            String value = objectMapper.writeValueAsString(
                    Map.of("name", displayName, "connectedAt", Instant.now().getEpochSecond()));
            cache.hPut(ONLINE_AGENTS_KEY, agentId, value, ONLINE_HASH_TTL);
            log.debug("[SessionQueue] 座席上线 agentId={} name={}", agentId, displayName);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[SessionQueue] 注册座席序列化失败 agentId={}", agentId, e);
        }
    }

    /** 注销座席下线（SSE 连接断开时调用） */
    public void deregisterAgent(String agentId) {
        cache.hDelete(ONLINE_AGENTS_KEY, agentId);
        log.debug("[SessionQueue] 座席下线 agentId={}", agentId);
    }

    /** 获取在线座席列表，统计每个座席当前的 ACTIVE 会话数 */
    public List<OnlineAgentVO> getOnlineAgents() {
        Map<Object, Object> agentMap = cache.hEntries(ONLINE_AGENTS_KEY);
        if (agentMap.isEmpty()) {
            return List.of();
        }

        // 统计每个 agentId 的 ACTIVE 会话数（容量按 agentMap 估算，避免扩容）
        Map<String, Long> activeCount = new HashMap<>((int) (agentMap.size() / 0.75f) + 1);
        cache.hEntries(QUEUE_KEY).forEach((k, v) -> {
            try {
                SessionQueueItem item = objectMapper.readValue((String) v, SessionQueueItem.class);
                if (item.status() == SessionStatus.ACTIVE && item.agentId() != null) {
                    activeCount.merge(item.agentId(), 1L, Long::sum);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // 忽略坏数据，详细日志已在 getByStatus 中输出
            }
        });

        List<OnlineAgentVO> result = new ArrayList<>();
        agentMap.forEach((agentId, agentJson) -> {
            try {
                Map<?, ?> info = objectMapper.readValue((String) agentJson, Map.class);
                String name = (String) info.get("name");
                long sessions = activeCount.getOrDefault((String) agentId, 0L);
                result.add(new OnlineAgentVO((String) agentId, name, sessions));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("[SessionQueue] 解析在线座席失败 agentId={}", agentId, e);
            }
        });
        result.sort(Comparator.comparing(OnlineAgentVO::sessions));
        return result;
    }

    // ---- 内部：事件广播 ----

    /** 向 Fanout Exchange 发布队列状态变更事件 */
    private void publishEvent(SessionEvent event) {
        try {
            rabbitTemplate.convertAndSend(eventsExchange, "", event);
        } catch (org.springframework.amqp.AmqpException e) {
            log.error("[SessionQueue] Fanout 事件发布失败", e);
        }
    }

    private void publishSessionStart(String sessionId, String visitorName,
                                     String transferReason, String tag, long timestamp) {
        try {
            publisher.publishSessionStart(sessionId, visitorName, transferReason, tag, timestamp);
        } catch (org.springframework.amqp.AmqpException e) {
            log.warn("[SessionQueue] SESSION_START MQ 发布失败 sessionId={}", sessionId, e);
        }
    }

    private void publishSessionAccept(String sessionId, String agentId, long timestamp) {
        try {
            publisher.publishSessionAccept(sessionId, agentId, timestamp);
        } catch (org.springframework.amqp.AmqpException e) {
            log.warn("[SessionQueue] SESSION_ACCEPT MQ 发布失败 sessionId={}", sessionId, e);
        }
    }

    private void publishSessionTransfer(String sessionId, String fromAgentId,
                                        String toAgentId, long timestamp) {
        try {
            publisher.publishSessionTransfer(sessionId, fromAgentId, toAgentId, timestamp);
        } catch (org.springframework.amqp.AmqpException e) {
            log.warn("[SessionQueue] SESSION_TRANSFER MQ 发布失败 sessionId={}", sessionId, e);
        }
    }

    private void publishSessionEnd(String sessionId) {
        try {
            publisher.publishSessionEnd(sessionId);
        } catch (org.springframework.amqp.AmqpException e) {
            log.warn("[SessionQueue] SESSION_END MQ 发布失败 sessionId={}", sessionId, e);
        }
    }

    // ---- VO ----

    /** 在线座席信息 VO */
    public record OnlineAgentVO(String id, String name, long sessions) {}

    /**
     * 会话队列项。
     * agentId：接入座席 ID，WAITING 状态时为 null，ACTIVE 时填入接入座席 ID。
     */
    public record SessionQueueItem(
            String sessionId,
            String userName,
            String transferReason,
            String tag,
            long waitSince,
            SessionStatus status,
            String agentId
    ) {}

    /**
     * 会话队列事件，广播给所有座席 SSE 连接。
     *
     * @param type        事件类型
     * @param item        会话项（含 agentId，转交后 agentId = 目标座席）
     * @param fromAgentId 仅 TRANSFER 事件有值，源座席 ID
     * @param toAgentId   仅 TRANSFER 事件有值，目标座席 ID
     */
    public record SessionEvent(
            SessionEventType type,
            SessionQueueItem item,
            String fromAgentId,
            String toAgentId
    ) {
        /** 普通事件（非转交）便捷构造器 */
        public SessionEvent(SessionEventType type, SessionQueueItem item) {
            this(type, item, null, null);
        }
    }
}
