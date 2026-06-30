package com.aidevplatform.conversation.application.service;

import com.aidevplatform.conversation.application.exception.SessionEnqueueException;
import com.aidevplatform.conversation.domain.SessionAlreadyAcceptedException;
import com.aidevplatform.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aidevplatform.conversation.domain.SessionEventType;
import com.aidevplatform.conversation.domain.SessionStatus;
import com.aidevplatform.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
    private static final String QUEUE_KEY         = "agent:session:queue";
    /** 在线座席注册表，Hash：{agentId → JSON{name,connectedAt}} */
    private static final String ONLINE_AGENTS_KEY = "agent:online";

    /**
     * Lua CAS 脚本：原子地检查并更新会话状态。
     *
     * <p>KEYS[1] = Hash key（agent:session:queue）
     * <p>ARGV[1] = sessionId（Hash field）
     * <p>ARGV[2] = 期望的当前状态 JSON 中的 status 字段值（如 "WAITING"）
     * <p>ARGV[3] = 更新后的完整 JSON 字符串
     *
     * <p>返回值：1 表示 CAS 成功；0 表示 field 不存在或状态不符（已被抢占）。
     */
    private static final String ACCEPT_CAS_LUA =
            "local val = redis.call('HGET', KEYS[1], ARGV[1])\n" +
            "if val == false then return 0 end\n" +
            // plain=true（第4个参数）关闭 Lua 模式匹配，与 TRANSFER_CAS_LUA 保持一致
            "if string.find(val, ARGV[2], 1, true) == nil then return 0 end\n" +
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])\n" +
            "return 1";

    /** 包装后的 Lua 脚本，Spring Data Redis 执行时自动缓存 SHA1 */
    private static final RedisScript<Long> ACCEPT_CAS_SCRIPT =
            new DefaultRedisScript<>(ACCEPT_CAS_LUA, Long.class);

    /**
     * 转交 CAS 脚本：原子地校验当前 agentId 后更新会话。
     *
     * <p>KEYS[1] = QUEUE_KEY；ARGV[1] = sessionId；
     * <p>ARGV[2] = 期望的源座席 ID 关键词（如 "\"agentId\":\"alice\""），用于校验当前会话归属
     * <p>ARGV[3] = 更新后的完整 JSON 字符串
     *
     * <p>返回 1=CAS 成功；0=会话不存在或源座席不匹配（已被他人转走）。
     */
    private static final String TRANSFER_CAS_LUA =
            "local val = redis.call('HGET', KEYS[1], ARGV[1])\n" +
            "if val == false then return 0 end\n" +
            "if string.find(val, ARGV[2], 1, true) == nil then return 0 end\n" +
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])\n" +
            "return 1";

    private static final RedisScript<Long> TRANSFER_CAS_SCRIPT =
            new DefaultRedisScript<>(TRANSFER_CAS_LUA, Long.class);

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
                Instant.now().getEpochSecond(), SessionStatus.WAITING, null
        );
        // Redis 写入失败为不可恢复错误，直接抛出让 Controller 返回 503
        try {
            redis.opsForHash().put(QUEUE_KEY, sessionId, objectMapper.writeValueAsString(item));
        } catch (Exception e) {
            log.error("[SessionQueue] enqueue Redis 写入失败 sessionId={}", sessionId, e);
            throw new SessionEnqueueException("会话入队失败，请稍后重试", sessionId, e);
        }
        // MQ 发布失败为可降级场景：座席刷新队列仍可看到（数据在 Redis），仅记录警告
        publishEvent(new SessionEvent(SessionEventType.ENQUEUE, item));
        publishSessionStart(sessionId, userName, transferReason, tag, item.waitSince());
        log.info("[SessionQueue] enqueue sessionId={} userName={}", sessionId, userName);
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
                        SessionStatus.ACTIVE,
                        null))
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
     *
     * <p>使用 Lua CAS 脚本保证原子性，防止两名座席并发接入同一会话（TOCTOU 竞态）。
     *
     * @param sessionId 会话唯一标识
     * @param agentId   接入座席 ID（从 token 中解析）
     * @return 更新后的会话队列项
     */
    public SessionQueueItem accept(String sessionId, String agentId) {
        try {
            // 先读取当前值，构造更新后的 JSON
            Object raw = redis.opsForHash().get(QUEUE_KEY, sessionId);
            if (raw == null) {
                throw new IllegalArgumentException("会话不存在: " + sessionId);
            }
            SessionQueueItem old = objectMapper.readValue((String) raw, SessionQueueItem.class);
            // 状态机校验（非 WAITING 状态时此处已抛出异常）
            SessionStatus newStatus = old.status().transitionTo(SessionStatus.ACTIVE);
            SessionQueueItem updated = new SessionQueueItem(
                    old.sessionId(), old.userName(), old.transferReason(),
                    old.tag(), old.waitSince(), newStatus, agentId
            );
            String updatedJson = objectMapper.writeValueAsString(updated);

            // Lua CAS：检查 JSON 中包含 "WAITING" 字样再写入，防止并发抢占
            // KEYS[1]=Hash key，ARGV[1]=field，ARGV[2]=期望状态关键词，ARGV[3]=新 JSON
            Long result = redis.execute(
                    ACCEPT_CAS_SCRIPT,
                    Collections.singletonList(QUEUE_KEY),
                    sessionId, "\"status\":\"WAITING\"", updatedJson
            );

            if (result == 0L) {
                // CAS 失败：已被其他座席抢先接入
                throw new SessionAlreadyAcceptedException(sessionId);
            }

            // CAS 成功后广播事件和持久化
            publishEvent(new SessionEvent(SessionEventType.ACCEPTED, updated));
            publishSessionAccept(sessionId, agentId, Instant.now().getEpochSecond());
            log.info("[SessionQueue] accept 成功 sessionId={}", sessionId);
            return updated;
        } catch (SessionAlreadyAcceptedException | IllegalArgumentException e) {
            throw e;
        } catch (IllegalStateException e) {
            // 非 WAITING 状态时 transitionTo 抛出 IllegalStateException，统一翻译为 409
            throw new SessionAlreadyAcceptedException(sessionId);
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
                        old.tag(), old.waitSince(), newStatus, old.agentId()
                );
                publishEvent(new SessionEvent(SessionEventType.CLOSED, closed));
                redis.opsForHash().delete(QUEUE_KEY, sessionId);
            } else {
                // Redis 已无数据（重启/驱逐）：仅广播最小事件，Redis HDEL 幂等无害
                log.warn("[SessionQueue] close 时 Redis 无数据（可能已重启），仍执行 DB 关闭 sessionId={}", sessionId);
                SessionQueueItem minimal = new SessionQueueItem(sessionId, "", "", "", 0L, SessionStatus.CLOSED, null);
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

    /**
     * 转交会话给指定座席（当前座席 → 目标座席，状态保持 ACTIVE）。
     *
     * <p>使用 Lua CAS 保证原子性，校验当前 agentId 匹配后才更新，
     * 防止两名座席同时点击转交按钮产生丢失写。
     *
     * @param sessionId     会话唯一标识
     * @param fromAgentId   发起转交的源座席 ID（必须等于会话当前 agentId）
     * @param targetAgentId 目标座席 ID
     * @throws IllegalArgumentException 会话不存在 / 目标座席不在线
     * @throws IllegalStateException    状态不是 ACTIVE / 源座席不匹配（CAS 失败）
     */
    public void transfer(String sessionId, String fromAgentId, String targetAgentId) {
        // 校验目标座席在线
        Boolean online = redis.opsForHash().hasKey(ONLINE_AGENTS_KEY, targetAgentId);
        if (!Boolean.TRUE.equals(online)) {
            throw new IllegalArgumentException("目标座席不在线: " + targetAgentId);
        }
        try {
            Object raw = redis.opsForHash().get(QUEUE_KEY, sessionId);
            if (raw == null) {
                throw new IllegalArgumentException("会话不存在: " + sessionId);
            }
            SessionQueueItem old = objectMapper.readValue((String) raw, SessionQueueItem.class);
            if (old.status() != SessionStatus.ACTIVE) {
                throw new IllegalStateException("只有 ACTIVE 状态的会话才能转交，当前状态: " + old.status());
            }
            SessionQueueItem transferred = new SessionQueueItem(
                    old.sessionId(), old.userName(), old.transferReason(),
                    old.tag(), old.waitSince(), SessionStatus.ACTIVE, targetAgentId
            );
            String updatedJson = objectMapper.writeValueAsString(transferred);

            // Lua CAS：JSON 中必须包含源 agentId 才能写入，防止并发转交丢失
            String expectedAgentMarker = "\"agentId\":\"" + fromAgentId + "\"";
            Long result = redis.execute(
                    TRANSFER_CAS_SCRIPT,
                    Collections.singletonList(QUEUE_KEY),
                    sessionId, expectedAgentMarker, updatedJson
            );

            if (result == null || result == 0L) {
                throw new IllegalStateException("会话归属已变更，无法转交: " + sessionId);
            }

            // 广播 TRANSFER 事件（前端：发起方移除会话，目标方自动接入）
            publishEvent(new SessionEvent(
                    SessionEventType.TRANSFER, transferred, fromAgentId, targetAgentId));
            // 持久化：DB 中 agent_id 更新为 toAgentId（source of truth）
            publishSessionTransfer(sessionId, fromAgentId, targetAgentId,
                    Instant.now().getEpochSecond());
            log.info("[SessionQueue] 会话转交 sessionId={} {} → {}",
                    sessionId, fromAgentId, targetAgentId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[SessionQueue] transfer error sessionId={}", sessionId, e);
            throw new RuntimeException(e);
        }
    }

    // ---- 在线座席注册表 ----

    /**
     * 注册座席上线（SSE 连接建立时调用）。
     *
     * @param agentId     座席 ID
     * @param displayName 座席显示名称
     */
    public void registerAgent(String agentId, String displayName) {
        try {
            String value = objectMapper.writeValueAsString(
                    Map.of("name", displayName, "connectedAt", Instant.now().getEpochSecond()));
            redis.opsForHash().put(ONLINE_AGENTS_KEY, agentId, value);
            log.debug("[SessionQueue] 座席上线 agentId={} name={}", agentId, displayName);
        } catch (Exception e) {
            log.warn("[SessionQueue] 注册座席失败 agentId={}", agentId, e);
        }
    }

    /**
     * 注销座席下线（SSE 连接断开时调用）。
     *
     * @param agentId 座席 ID
     */
    public void deregisterAgent(String agentId) {
        redis.opsForHash().delete(ONLINE_AGENTS_KEY, agentId);
        log.debug("[SessionQueue] 座席下线 agentId={}", agentId);
    }

    /**
     * 获取在线座席列表，统计每个座席当前的 ACTIVE 会话数。
     *
     * @return 在线座席 VO 列表
     */
    public List<OnlineAgentVO> getOnlineAgents() {
        Map<Object, Object> agentMap = redis.opsForHash().entries(ONLINE_AGENTS_KEY);
        if (agentMap.isEmpty()) return Collections.emptyList();

        // 统计每个 agentId 的 ACTIVE 会话数
        Map<String, Long> activeCount = new HashMap<>();
        redis.opsForHash().entries(QUEUE_KEY).forEach((k, v) -> {
            try {
                SessionQueueItem item = objectMapper.readValue((String) v, SessionQueueItem.class);
                if (item.status() == SessionStatus.ACTIVE && item.agentId() != null) {
                    activeCount.merge(item.agentId(), 1L, Long::sum);
                }
            } catch (Exception ignored) {}
        });

        List<OnlineAgentVO> result = new ArrayList<>();
        agentMap.forEach((agentId, agentJson) -> {
            try {
                Map<?, ?> info = objectMapper.readValue((String) agentJson, Map.class);
                String name = (String) info.get("name");
                long sessions = activeCount.getOrDefault((String) agentId, 0L);
                result.add(new OnlineAgentVO((String) agentId, name, sessions));
            } catch (Exception e) {
                log.warn("[SessionQueue] 解析在线座席失败 agentId={}", agentId, e);
            }
        });
        result.sort(Comparator.comparing(OnlineAgentVO::sessions));
        return result;
    }

    // ---- VO ----

    /** 在线座席信息 VO */
    public record OnlineAgentVO(String id, String name, long sessions) {}

    // ---- 内部：事件广播 ----

    /**
     * 向 {@code cs.conversation.events} Fanout Exchange 发布队列状态变更事件。
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
     * Consumer 消费后将 DB 状态从 WAITING 更新为 ACTIVE，并写入 agentId。
     */
    private void publishSessionAccept(String sessionId, String agentId, long timestamp) {
        try {
            publisher.publishSessionAccept(sessionId, agentId, timestamp);
        } catch (Exception e) {
            log.warn("[SessionQueue] SESSION_ACCEPT MQ 发布失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 发布会话转交事件到持久化 RabbitMQ Direct Exchange。
     * Consumer 消费后将 DB 中的 agent_id 更新为目标座席。
     */
    private void publishSessionTransfer(String sessionId, String fromAgentId,
                                        String toAgentId, long timestamp) {
        try {
            publisher.publishSessionTransfer(sessionId, fromAgentId, toAgentId, timestamp);
        } catch (Exception e) {
            log.warn("[SessionQueue] SESSION_TRANSFER MQ 发布失败 sessionId={}", sessionId, e);
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
