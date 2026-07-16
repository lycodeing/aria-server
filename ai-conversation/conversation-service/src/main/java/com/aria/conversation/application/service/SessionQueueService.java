package com.aria.conversation.application.service;

import com.aria.conversation.application.exception.SessionEnqueueException;
import com.aria.conversation.domain.SessionAlreadyAcceptedException;
import com.aria.conversation.domain.SessionEventType;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.config.CsAgentConfigProvider;
import com.aria.conversation.infrastructure.csat.CsatRatingDO;
import com.aria.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.repository.AgentOnlineRegistry;
import com.aria.conversation.infrastructure.repository.SessionQueueRepository;
import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 会话队列服务。
 *
 * <p>职责：
 * <ol>
 *   <li>会话入队/接入/关闭/转交（委托给 {@link SessionQueueRepository}）</li>
 *   <li>座席在线状态管理（委托给 {@link AgentOnlineRegistry}）</li>
 *   <li>通过 RabbitMQ Fanout {@code cs.conversation.events} 实时通知座席端 SSE</li>
 *   <li>通过 RabbitMQ Direct {@code cs.conversation} 发布生命周期事件，
 *       供 ConversationMessageConsumer 异步消费并持久化到 PostgreSQL</li>
 * </ol>
 *
 * <p>本类不直接操作 Redis，所有 Redis 细节由 Repository 层封装。
 *
 * <p>状态机（由 {@link SessionStatus} 枚举保证合法转换）：
 * <pre>WAITING → ACTIVE → CLOSED</pre>
 */
@Slf4j
@Service
public class SessionQueueService {

    /** agentId 合法字符集校验（防注入，与 Controller 层保持一致） */
    private static final Pattern AGENT_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    private final SessionQueueRepository         queueRepository;
    private final AgentOnlineRegistry            agentRegistry;
    private final ConversationMessagePublisher   publisher;
    private final RabbitTemplate                 rabbitTemplate;
    private final String                         eventsExchange;
    private final ConversationPersistRepository  persistRepository;
    private final CsatService                    csatService;
    private final VisitorNotifier                visitorNotifier;
    private final CsAgentConfigProvider          csAgentConfigProvider;
    private final RedissonClient                 redissonClient;

    public SessionQueueService(
            SessionQueueRepository queueRepository,
            AgentOnlineRegistry agentRegistry,
            ConversationMessagePublisher publisher,
            @Qualifier("eventsRabbitTemplate") RabbitTemplate rabbitTemplate,
            @Value("${conversation.events.exchange}") String eventsExchange,
            ConversationPersistRepository persistRepository,
            CsatService csatService,
            VisitorNotifier visitorNotifier,
            CsAgentConfigProvider csAgentConfigProvider,   // 新增
            RedissonClient redissonClient) {               // 新增
        this.queueRepository      = queueRepository;
        this.agentRegistry        = agentRegistry;
        this.publisher            = publisher;
        this.rabbitTemplate       = rabbitTemplate;
        this.eventsExchange       = eventsExchange;
        this.persistRepository    = persistRepository;
        this.csatService          = csatService;
        this.visitorNotifier      = visitorNotifier;
        this.csAgentConfigProvider = csAgentConfigProvider;
        this.redissonClient        = redissonClient;
    }

    // ---- 队列操作 ----

    /**
     * 用户请求转人工，优先尝试自动分配给空闲客服，无空闲客服时加入等待队列。
     * 广播 Fanout 事件，并向持久化 Direct Exchange 发布 SESSION_START 事件。
     */
    public SessionQueueItem enqueue(String sessionId, String userName,
                                    String transferReason, String tag) {
        SessionQueueItem item = new SessionQueueItem(
                sessionId, userName, transferReason, tag,
                Instant.now().getEpochSecond(), SessionStatus.WAITING, null
        );
        // 优先尝试推送给有空余名额的在线客服，避免访客进入排队等待
        boolean dispatched = tryAutoDispatch(sessionId, item);
        if (dispatched) {
            return item; // 已自动分配，不需要写 WAITING 或广播队列更新
        }
        // 无空闲客服：持久化 WAITING，广播通知所有在线客服
        try {
            queueRepository.save(item);
        } catch (IllegalStateException e) {
            log.error("[SessionQueue] enqueue 失败 sessionId={}", sessionId, e);
            throw new SessionEnqueueException("会话入队失败，请稍后重试", sessionId, e);
        }
        publishEvent(new SessionEvent(SessionEventType.ENQUEUE, item));
        publishSessionStart(sessionId, userName, transferReason, tag, item.waitSince());
        log.info("[SessionQueue] enqueue WAITING sessionId={} userName={}", sessionId, userName);
        return item;
    }

    /** 查询等待队列（所有 WAITING 状态） */
    public List<SessionQueueItem> getQueue() {
        return queueRepository.findByStatus(SessionStatus.WAITING);
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
                        e.getAgentId()))
                .toList();
    }

    /**
     * 统一查询所有状态的会话，供座席工作台一次性加载四个 Tab 数据。
     *
     * <p>数据来源：
     * <ul>
     *   <li>AI_CHAT — DB（ended_at IS NULL 的活跃 AI 对话）</li>
     *   <li>WAITING  — Redis（等待人工接入的队列）</li>
     *   <li>ACTIVE   — DB（已被座席接入的进行中会话）</li>
     *   <li>CLOSED   — DB（最近 closedLimit 条已结束会话，按 updated_at 倒序）</li>
     * </ul>
     *
     * @param closedLimit CLOSED 状态返回条数上限，建议不超过 200
     * @return 四种状态会话的合并列表，顺序：AI_CHAT → WAITING → ACTIVE → CLOSED
     */
    public List<SessionQueueItem> getAllSessions(int closedLimit) {
        List<SessionQueueItem> result = new java.util.ArrayList<>();

        // AI_CHAT：DB 中 ended_at 为 null 的活跃 AI 对话
        persistRepository.getAiChatConversations().stream()
                .map(e -> new SessionQueueItem(
                        e.getSessionId(),
                        e.getVisitorName(),
                        e.getTransferReason(),
                        e.getTag(),
                        e.getStartedAt() != null ? e.getStartedAt().toEpochSecond() : 0L,
                        SessionStatus.AI_CHAT,
                        e.getAgentId()))
                .forEach(result::add);

        // WAITING：Redis 队列
        result.addAll(queueRepository.findByStatus(SessionStatus.WAITING));

        // ACTIVE：DB（刷新恢复 source of truth）
        persistRepository.getActiveConversations().stream()
                .map(e -> new SessionQueueItem(
                        e.getSessionId(),
                        e.getVisitorName(),
                        e.getTransferReason(),
                        e.getTag(),
                        e.getStartedAt() != null ? e.getStartedAt().toEpochSecond() : 0L,
                        SessionStatus.ACTIVE,
                        e.getAgentId()))
                .forEach(result::add);

        // CLOSED：DB 最近 closedLimit 条
        int safeLimit = Math.min(Math.max(closedLimit, 1), 200);
        persistRepository.getClosedConversations(safeLimit).stream()
                .map(e -> new SessionQueueItem(
                        e.getSessionId(),
                        e.getVisitorName(),
                        e.getTransferReason(),
                        e.getTag(),
                        e.getUpdatedAt() != null ? e.getUpdatedAt().toEpochSecond()
                                : e.getStartedAt() != null ? e.getStartedAt().toEpochSecond() : 0L,
                        e.getStatus(),
                        e.getAgentId()))
                .forEach(result::add);

        return result;
    }

    /**
     * 幂等初始化 AI_CHAT 会话记录（ChatAppService 在首条消息时调用）。
     * 若记录已存在，静默跳过；若已是 WAITING/ACTIVE，同样跳过（转人工流程已覆盖）。
     *
     * @param sessionId 会话唯一标识
     */
    public void initAiChatSession(String sessionId) {
        persistRepository.initAiChatSession(sessionId, OffsetDateTime.now());
    }

    /**
     * 查询最近历史会话，供座席工作台「已结束」Tab 展示。
     * 包含 status=CLOSED（转人工已结束）和 status=AI_CHAT（纯 AI 对话）两类，
     * 按 updated_at 倒序，最多返回 limit 条。
     *
     * @param limit 返回条数上限，建议不超过 200
     */
    public List<SessionQueueItem> getClosedSessions(int limit) {
        return persistRepository.getClosedConversations(limit).stream()
                .map(e -> new SessionQueueItem(
                        e.getSessionId(),
                        e.getVisitorName(),
                        e.getTransferReason(),
                        e.getTag(),
                        e.getUpdatedAt() != null ? e.getUpdatedAt().toEpochSecond()
                                : e.getStartedAt() != null ? e.getStartedAt().toEpochSecond() : 0L,
                        e.getStatus(),
                        e.getAgentId()))
                .toList();
    }

    /**
     * 座席接入会话，状态 WAITING → ACTIVE。
     * CAS 原子操作由 {@link SessionQueueRepository#compareAndSetStatus} 保证，
     * 防止两名座席并发抢接同一会话。
     */
    public SessionQueueItem accept(String sessionId, String agentId) {
        SessionQueueItem old = queueRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));

        SessionStatus newStatus;
        try {
            newStatus = old.status().transitionTo(SessionStatus.ACTIVE);
        } catch (IllegalStateException e) {
            // 非 WAITING 状态，统一翻译为 409
            throw new SessionAlreadyAcceptedException(sessionId);
        }

        SessionQueueItem updated = new SessionQueueItem(
                old.sessionId(), old.userName(), old.transferReason(),
                old.tag(), old.waitSince(), newStatus, agentId
        );
        boolean ok = queueRepository.compareAndSetStatus(sessionId, updated);
        if (!ok) {
            throw new SessionAlreadyAcceptedException(sessionId);
        }

        publishEvent(new SessionEvent(SessionEventType.ACCEPTED, updated));
        publishSessionAccept(sessionId, agentId, Instant.now().getEpochSecond());
        log.info("[SessionQueue] accept 成功 sessionId={}", sessionId);
        return updated;
    }

    /**
     * 结束会话，从队列中移除，并向持久化 Direct Exchange 发布 SESSION_END 事件。
     * DB 关闭操作与 Redis 状态解耦：无论 Redis 有无数据都执行 DB 关闭。
     *
     * @param sessionId 会话唯一标识
     * @param closedBy  关闭发起方（agent / visitor / system）
     */
    public void close(String sessionId, String closedBy) {
        try {
            String[] agentIdHolder = {null};
            queueRepository.findById(sessionId).ifPresentOrElse(
                old -> {
                    agentIdHolder[0] = old.agentId();
                    SessionStatus newStatus = old.status().transitionTo(SessionStatus.CLOSED);
                    SessionQueueItem closed = new SessionQueueItem(
                            old.sessionId(), old.userName(), old.transferReason(),
                            old.tag(), old.waitSince(), newStatus, old.agentId()
                    );
                    publishEvent(new SessionEvent(SessionEventType.CLOSED, closed));
                },
                () -> {
                    log.warn("[SessionQueue] close 时 Redis 无数据（可能已重启）仍执行 DB 关闭 sessionId={}", sessionId);
                    SessionQueueItem minimal = new SessionQueueItem(
                            sessionId, "", "", "", 0L, SessionStatus.CLOSED, null);
                    publishEvent(new SessionEvent(SessionEventType.CLOSED, minimal));
                }
            );
            queueRepository.delete(sessionId); // 幂等，无数据时 no-op
            publishSessionEnd(sessionId, closedBy);
            triggerCsatAsync(sessionId, agentIdHolder[0]);
        } catch (IllegalStateException e) {
            log.warn("[SessionQueue] close 状态机校验失败 sessionId={} msg={}", sessionId, e.getMessage());
        }
    }

    /**
     * 检查会话是否已被座席接入（供 ChatController 判断是否还走 AI）。
     * 优先查 Redis（快），Redis 缺失时兜底查 DB。
     */
    public boolean isActive(String sessionId) {
        return queueRepository.findById(sessionId)
                .map(item -> SessionStatus.ACTIVE == item.status())
                .orElseGet(() -> {
                    boolean dbActive = persistRepository.isActiveInDb(sessionId);
                    if (dbActive) log.debug("[SessionQueue] Redis 缺失，DB 兜底确认 ACTIVE sessionId={}", sessionId);
                    return dbActive;
                });
    }

    /**
     * 查询会话当前状态（供前端 onMounted 兜底检测转接状态使用）。
     *
     * <p>优先查 Redis，Redis 缺失（TTL 过期或 Redis 重启）时兜底查 DB。
     * 若会话不存在，返回 {@link SessionStatus#AI_CHAT}。
     *
     * @param sessionId 会话 ID
     * @return 当前会话状态
     */
    public SessionStatus getSessionStatus(String sessionId) {
        return queueRepository.findById(sessionId)
                .map(SessionQueueItem::status)
                .orElseGet(() -> {
                    SessionStatus dbStatus = persistRepository.getStatusFromDb(sessionId);
                    if (dbStatus != null) {
                        log.debug("[SessionQueue] Redis 缺失，DB 兜底查询 status={} sessionId={}",
                                dbStatus, sessionId);
                        return dbStatus;
                    }
                    return SessionStatus.AI_CHAT;
                });
    }

    /**
     * 转交会话给指定座席（当前座席 → 目标座席，状态保持 ACTIVE）。
     * CAS 原子操作由 {@link SessionQueueRepository#compareAndSetAgentId} 保证。
     */
    public void transfer(String sessionId, String fromAgentId, String targetAgentId) {
        if (fromAgentId == null || !AGENT_ID_PATTERN.matcher(fromAgentId).matches()) {
            throw new IllegalArgumentException("fromAgentId 格式非法: " + fromAgentId);
        }
        if (targetAgentId == null || !AGENT_ID_PATTERN.matcher(targetAgentId).matches()) {
            throw new IllegalArgumentException("targetAgentId 格式非法: " + targetAgentId);
        }
        if (!agentRegistry.isOnline(targetAgentId)) {
            throw new IllegalArgumentException("目标座席不在线: " + targetAgentId);
        }

        SessionQueueItem old = queueRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        if (old.status() != SessionStatus.ACTIVE) {
            throw new IllegalStateException("只有 ACTIVE 状态的会话才能转交，当前状态: " + old.status());
        }

        SessionQueueItem transferred = new SessionQueueItem(
                old.sessionId(), old.userName(), old.transferReason(),
                old.tag(), old.waitSince(), SessionStatus.ACTIVE, targetAgentId
        );
        boolean ok = queueRepository.compareAndSetAgentId(sessionId, fromAgentId, transferred);
        if (!ok) {
            throw new IllegalStateException("会话归属已变更，无法转交: " + sessionId);
        }

        publishEvent(new SessionEvent(SessionEventType.TRANSFER, transferred, fromAgentId, targetAgentId));
        publishSessionTransfer(sessionId, fromAgentId, targetAgentId, Instant.now().getEpochSecond());
        log.info("[SessionQueue] 会话转交 sessionId={} {} → {}", sessionId, fromAgentId, targetAgentId);
    }

    /**
     * 查询会话当前负责的座席 ID。
     *
     * <p>用于 WS 消息路由：{@code ChatWebSocketHandler.notifyAgent} 通过此方法
     * 将 sessionId 转换为 agentId，再交由 {@code AgentConnectionRegistry} 广播。
     *
     * @param sessionId 会话 ID
     * @return 负责此会话的座席 ID；会话处于 WAITING 状态或不存在时返回 {@code null}
     */
    public String getAgentId(String sessionId) {
        return queueRepository.findById(sessionId)
                .map(SessionQueueItem::agentId)
                .orElse(null);
    }

    // ---- 在线座席注册表 ----

    /** 注册座席上线（SSE 连接建立时调用）。 */
    public void registerAgent(String agentId, String displayName) {
        agentRegistry.register(agentId, displayName);
    }

    /** 注销座席下线（SSE 连接断开时调用）。引用计数归零后才真正下线。 */
    public void deregisterAgent(String agentId) {
        agentRegistry.deregister(agentId);
    }

    /** 获取在线座席列表，统计每个座席当前的 ACTIVE 会话数。 */
    public List<OnlineAgentVO> getOnlineAgents() {
        List<AgentOnlineRegistry.AgentInfo> agents = agentRegistry.findAll();
        if (agents.isEmpty()) {
            return List.of();
        }

        Map<String, Long> activeCount = new HashMap<>((int) (agents.size() / 0.75f) + 1);
        queueRepository.findAll().forEach(item -> {
            if (item.status() == SessionStatus.ACTIVE && item.agentId() != null) {
                activeCount.merge(item.agentId(), 1L, Long::sum);
            }
        });

        return agents.stream()
                .map(a -> new OnlineAgentVO(
                        a.agentId(), a.name(),
                        activeCount.getOrDefault(a.agentId(), 0L)))
                .sorted(Comparator.comparing(OnlineAgentVO::sessions))
                .toList();
    }

    /**
     * 异步触发 CSAT 邀请推送，不阻塞会话关闭主流程。
     * 推送失败只记录日志，不影响关闭结果。
     */
    @Async
    void triggerCsatAsync(String sessionId, String agentId) {
        try {
            Long agentIdLong = agentId != null && !agentId.isBlank()
                    ? Long.parseLong(agentId) : null;
            CsatRatingDO csat = csatService.createInvitation(
                    sessionId, null, agentIdLong, "HUMAN");
            visitorNotifier.notifyVisitor(sessionId, Map.of(
                    "type",      ChatEvent.EventType.CSAT_REQUEST,
                    "csatId",    csat.getId(),
                    "sessionId", sessionId,
                    "message",   "请对本次服务进行评价",
                    "expiresAt", csat.getExpiredAt().toString()
            ));
            log.info("[CSAT] 人工会话关闭触发评价邀请 sessionId={} csatId={}", sessionId, csat.getId());
        } catch (Exception e) {
            log.warn("[CSAT] 触发评价邀请失败 sessionId={}", sessionId, e);
        }
    }

    // ---- 自动分配：tryAutoDispatch 及辅助方法 ----

    /**
     * 尝试将会话自动分配给负载最低的空闲客服。
     * 按 sessions 升序遍历候选，第一个通过加锁+二次校验的客服即为目标。
     *
     * @return true=分配成功；false=无空闲客服，调用方应写 WAITING
     */
    private boolean tryAutoDispatch(String sessionId, SessionQueueItem item) {
        int max = csAgentConfigProvider.getMaxSessionsPerAgent();
        for (OnlineAgentVO candidate : findAvailableCandidates(max)) {
            if (tryAssignNewSession(sessionId, item, candidate.id(), max)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从在线客服中筛选出 sessions < max 的候选列表（已按负载升序）。
     * 此处为乐观读，仅做粗筛，真正容量校验在持锁后进行。
     */
    private List<OnlineAgentVO> findAvailableCandidates(int max) {
        return getOnlineAgents().stream()
                .filter(a -> a.sessions() < max)
                .toList();
    }

    /**
     * 对单个候选客服：加锁 → 二次校验 → 执行分配。
     * 加锁失败或校验不通过均返回 false。
     */
    private boolean tryAssignNewSession(String sessionId, SessionQueueItem item,
                                        String agentId, int max) {
        return withAgentLock(agentId, () -> {
            if (!isAgentAvailable(agentId, max)) return false;
            doAssignNewSession(sessionId, item, agentId);
            return true;
        });
    }

    /**
     * 执行新会话的完整分配动作：Redis → MQ（SESSION_START + SESSION_ACCEPT）→ SSE fanout。
     * DB 由 MQ 消费者（ConversationMessageConsumer）异步写入，与现有 enqueue+accept 路径一致。
     */
    private void doAssignNewSession(String sessionId, SessionQueueItem item, String agentId) {
        SessionQueueItem activeItem = buildActiveItem(item, agentId);
        queueRepository.save(activeItem);
        publishSessionStart(sessionId, item.userName(), item.transferReason(),
                item.tag(), item.waitSince());
        publishSessionAccept(sessionId, agentId, Instant.now().getEpochSecond());
        publishEvent(new SessionEvent(SessionEventType.AUTO_ASSIGNED, activeItem));
        log.info("[AutoDispatch] 会话 {} 自动分配给客服 {}", sessionId, agentId);
    }

    /**
     * Redisson 分布式锁通用包装：tryLock(wait=0, TTL=3s) → action → unlock。
     * 加锁失败立即返回 false，不阻塞，不抛异常。
     */
    private boolean withAgentLock(String agentId, Supplier<Boolean> action) {
        RLock lock = redissonClient.getLock("lock:assign:agent:" + agentId);
        try {
            if (!lock.tryLock(0, 3, TimeUnit.SECONDS)) return false;
            try {
                return action.get();
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AgentLock] 加锁中断，agentId={}", agentId);
            return false;
        }
    }

    /**
     * 持锁后二次校验：ACTIVE 会话数 < max 且客服在线。
     */
    private boolean isAgentAvailable(String agentId, int max) {
        return countActiveSessions(agentId) < max && agentRegistry.isOnline(agentId);
    }

    /**
     * 统计指定客服当前 ACTIVE 会话数（全量扫描 Redis Hash）。
     */
    private long countActiveSessions(String agentId) {
        return queueRepository.findAll().stream()
                .filter(i -> agentId.equals(i.agentId()) && i.status() == SessionStatus.ACTIVE)
                .count();
    }

    /** 构造 ACTIVE 状态的队列项（record 不可变，返回新实例）。 */
    private SessionQueueItem buildActiveItem(SessionQueueItem item, String agentId) {
        return new SessionQueueItem(
                item.sessionId(), item.userName(), item.transferReason(),
                item.tag(), item.waitSince(), SessionStatus.ACTIVE, agentId);
    }

    // ---- 内部：事件广播 ----

    private void publishEvent(SessionEvent event) {
        try {
            rabbitTemplate.convertAndSend(eventsExchange, "", event);
        } catch (org.springframework.amqp.AmqpException e) {
            log.error("[SessionQueue] Fanout 事件发布失败", e);
        }
    }

    /**
     * 安全发布 MQ 事件的通用包装器。
     * 捕获 AmqpException 并记录 WARN，不阻断主流程。
     *
     * @param action    发布动作
     * @param eventName 事件名称（用于日志）
     * @param sessionId 会话 ID（用于日志）
     */
    private void publishSafely(Runnable action, String eventName, String sessionId) {
        try {
            action.run();
        } catch (org.springframework.amqp.AmqpException e) {
            log.warn("[SessionQueue] {} MQ 发布失败 sessionId={}", eventName, sessionId, e);
        }
    }

    private void publishSessionStart(String sessionId, String visitorName,
                                     String transferReason, String tag, long timestamp) {
        publishSafely(() -> publisher.publishSessionStart(sessionId, visitorName, transferReason, tag, timestamp),
                "SESSION_START", sessionId);
    }

    private void publishSessionAccept(String sessionId, String agentId, long timestamp) {
        publishSafely(() -> publisher.publishSessionAccept(sessionId, agentId, timestamp),
                "SESSION_ACCEPT", sessionId);
    }

    private void publishSessionTransfer(String sessionId, String fromAgentId,
                                        String toAgentId, long timestamp) {
        publishSafely(() -> publisher.publishSessionTransfer(sessionId, fromAgentId, toAgentId, timestamp),
                "SESSION_TRANSFER", sessionId);
    }

    private void publishSessionEnd(String sessionId, String closedBy) {
        publishSafely(() -> publisher.publishSessionEnd(sessionId, closedBy),
                "SESSION_END", sessionId);
    }

    // ---- VO ----

    /** 在线座席信息 VO */
    public record OnlineAgentVO(String id, String name, long sessions) {}

    /**
     * 会话队列事件，广播给所有座席 SSE 连接。
     *
     * @param type        事件类型
     * @param item        会话项
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
