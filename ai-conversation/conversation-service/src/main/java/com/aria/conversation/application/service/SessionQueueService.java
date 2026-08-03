package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.application.exception.ServiceOfflineException;
import com.aria.conversation.application.exception.SessionEnqueueException;
import com.aria.conversation.application.exception.SessionEnqueueMqFailedException;
import com.aria.conversation.domain.SessionAlreadyAcceptedException;
import com.aria.conversation.domain.SessionEventType;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.ClosedBy;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.domain.model.WebhookScope;
import com.aria.conversation.infrastructure.csat.CsatRatingDO;
import com.aria.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.webhook.WebhookEventContextFactory;
import com.aria.conversation.infrastructure.webhook.WebhookEventPublisher;
import com.aria.conversation.infrastructure.webhook.WebhookEventTypes;
import com.aria.conversation.infrastructure.repository.AgentOnlineRegistry;
import com.aria.conversation.infrastructure.repository.SessionQueueRepository;
import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
import com.aria.conversation.interfaces.rest.vo.TagVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    private static final String VISITOR_TAG_CACHE_PREFIX = "visitor:tags:";

    private final SessionQueueRepository         queueRepository;
    private final AgentOnlineRegistry            agentRegistry;
    private final ConversationMessagePublisher   publisher;
    private final RabbitTemplate                 rabbitTemplate;
    private final String                         eventsExchange;
    private final ConversationPersistRepository  persistRepository;
    private final CsatService                    csatService;
    private final VisitorNotifier                visitorNotifier;
    private final BusinessHoursService           businessHoursService;
    private final StringRedisTemplate            redisTemplate;
    private final ObjectMapper                   objectMapper;
    private final WebhookEventPublisher          webhookEventPublisher;

    public SessionQueueService(
            SessionQueueRepository queueRepository,
            AgentOnlineRegistry agentRegistry,
            ConversationMessagePublisher publisher,
            @Qualifier("eventsRabbitTemplate") RabbitTemplate rabbitTemplate,
            @Value("${conversation.events.exchange}") String eventsExchange,
            ConversationPersistRepository persistRepository,
            CsatService csatService,
            VisitorNotifier visitorNotifier,
            BusinessHoursService businessHoursService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            WebhookEventPublisher webhookEventPublisher) {
        this.queueRepository      = queueRepository;
        this.agentRegistry        = agentRegistry;
        this.publisher            = publisher;
        this.rabbitTemplate       = rabbitTemplate;
        this.eventsExchange       = eventsExchange;
        this.persistRepository    = persistRepository;
        this.csatService          = csatService;
        this.visitorNotifier      = visitorNotifier;
        this.businessHoursService = businessHoursService;
        this.redisTemplate        = redisTemplate;
        this.objectMapper         = objectMapper;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    // ---- 队列操作 ----

    /**
     * 用户请求转人工，加入等待队列，广播 Fanout 事件，
     * 并向持久化 Direct Exchange 发布 SESSION_START 事件。
     */
    public SessionQueueItem enqueue(String sessionId, String userName,
                                    String transferReason, String tag) {
        // 业务时间检查：非服务时间拒绝入队
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        if (!businessHoursService.isOpen(now)) {
            String nextOpen = businessHoursService.nextOpenTime(now);
            String msg = "当前不在服务时间，我们将在 " + nextOpen + " 恢复服务，感谢您的耐心等待。";
            throw new ServiceOfflineException(msg, nextOpen);
        }
        SessionQueueItem item = new SessionQueueItem(
                sessionId, userName, transferReason, tag,
                Instant.now().getEpochSecond(), SessionStatus.WAITING, null
        );
        boolean enqueued;
        try {
            // 原子入队：仅当会话尚不在队列中时写入，
            // 防止重复点击/并发把已被座席接入（ACTIVE）的会话覆盖回 WAITING
            enqueued = queueRepository.saveIfAbsent(item);
        } catch (IllegalStateException e) {
            log.error("[SessionQueue] enqueue 失败 sessionId={}", sessionId, e);
            throw new SessionEnqueueException("会话入队失败，请稍后重试", sessionId, e);
        }
        if (!enqueued) {
            // 会话已在队列（排队中或已接入），幂等返回现有项，不重复入队/重置排队位置
            SessionQueueItem existing = queueRepository.findById(sessionId).orElse(item);
            log.info("[SessionQueue] enqueue 跳过：会话已在队列 sessionId={} status={}",
                    sessionId, existing.status());
            return existing;
        }
        publishSessionLifecycleEvents(sessionId, userName, transferReason, tag, item);
        // 通用 Webhook：用户请求转人工（幂等：仅真正入队时发布）
        webhookEventPublisher.publish(WebhookScope.SESSION_TRANSFERRED,
                WebhookEventContextFactory.buildSessionEvent(
                        WebhookScope.SESSION_TRANSFERRED,
                        WebhookEventTypes.SESSION_ENQUEUE,
                        sessionId, userName,
                        Map.of(
                                "transferReason", transferReason == null ? "" : transferReason,
                                "tag", tag == null ? "" : tag)));
        log.info("[SessionQueue] enqueue sessionId={} userName={}", sessionId, userName);
        return item;
    }

    /**
     * 发布会话生命周期事件（Fanout + SESSION_START）。
     * Fanout 失败可容忍（坐席端下次刷新恢复）；SESSION_START 失败时补偿回滚 Redis 并抛专属异常。
     */
    private void publishSessionLifecycleEvents(String sessionId, String userName,
                                                String transferReason, String tag, SessionQueueItem item) {
        // Fanout 广播（非关键路径，失败可容忍——坐席端下次刷新恢复）
        publishEvent(new SessionEvent(SessionEventType.ENQUEUE, item));
        // SESSION_START（关键路径：触发 DB 持久化）
        // 直接调用 publisher（不经过 publishSafely 包装），让 AmqpException 上抛触发补偿回滚。
        // publishSafely 会吞掉 AmqpException，导致补偿回滚和 SessionEnqueueMqFailedException 成为死代码。
        try {
            publisher.publishSessionStart(sessionId, userName, transferReason, tag, item.waitSince());
        } catch (org.springframework.amqp.AmqpException e) {
            log.error("[SessionQueue] SESSION_START MQ 发布失败，补偿回滚 Redis 入队 sessionId={}", sessionId, e);
            queueRepository.delete(sessionId);
            throw new SessionEnqueueMqFailedException("服务暂时不可用，请稍后重试", sessionId, e);
        }
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
                .map(e -> toQueueItem(e, SessionStatus.ACTIVE, false))
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
                .map(e -> toQueueItem(e, SessionStatus.AI_CHAT, false))
                .forEach(result::add);

        // WAITING：Redis 队列
        result.addAll(queueRepository.findByStatus(SessionStatus.WAITING));

        // ACTIVE：DB（刷新恢复 source of truth）
        persistRepository.getActiveConversations().stream()
                .map(e -> toQueueItem(e, SessionStatus.ACTIVE, false))
                .forEach(result::add);

        // CLOSED：DB 最近 closedLimit 条（使用 updatedAt 作为时间戳）
        int safeLimit = Math.min(Math.max(closedLimit, 1), 200);
        persistRepository.getClosedConversations(safeLimit).stream()
                .map(e -> toQueueItem(e, e.getStatus(), true))
                .forEach(result::add);

        return result;
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
                .map(e -> toQueueItem(e, e.getStatus(), true))
                .toList();
    }

    /**
     * 将 DB 实体统一映射为 {@link SessionQueueItem}。
     * I3 修复：提取公共映射逻辑，消除 getActiveSessions / getAllSessions / getClosedSessions 三方法中
     * 近乎相同的 4 段构造代码。
     *
     * @param e           DB 会话实体
     * @param status      目标状态（AI_CHAT / ACTIVE / CLOSED）
     * @param useUpdatedAt true 时时间戳取 updatedAt（CLOSED 场景），false 时取 startedAt
     * @return 统一映射的队列项
     */
    private SessionQueueItem toQueueItem(
            com.aria.conversation.infrastructure.persistence.entity.ConversationEntity e,
            SessionStatus status, boolean useUpdatedAt) {
        long timestamp = useUpdatedAt
                ? (e.getUpdatedAt() != null ? e.getUpdatedAt().toEpochSecond()
                    : e.getStartedAt() != null ? e.getStartedAt().toEpochSecond() : 0L)
                : (e.getStartedAt() != null ? e.getStartedAt().toEpochSecond() : 0L);
        return new SessionQueueItem(
                e.getSessionId(),
                e.getVisitorName(),
                e.getTransferReason(),
                e.getTag(),
                timestamp,
                status,
                e.getAgentId(),
                loadVisitorTagsFromCache(e.getVisitorId()),
                e.getAcceptedAt() != null ? e.getAcceptedAt().toEpochSecond() : null);
    }

    /**
     * 座席接入会话，状态 WAITING → ACTIVE。
     * CAS 原子操作由 {@link SessionQueueRepository#compareAndSetStatus} 保证，
     * 防止两名座席并发抢接同一会话。
     */
    public SessionQueueItem accept(String sessionId, String agentId) {
        SessionQueueItem old = queueRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "会话不存在: " + sessionId));

        SessionStatus newStatus;
        try {
            newStatus = old.status().transitionTo(SessionStatus.ACTIVE);
        } catch (IllegalStateException e) {
            // 非 WAITING 状态，统一翻译为 409
            throw new SessionAlreadyAcceptedException(sessionId);
        }

        long acceptedAt = Instant.now().getEpochSecond();
        SessionQueueItem updated = new SessionQueueItem(
                old.sessionId(), old.userName(), old.transferReason(),
                old.tag(), old.waitSince(), newStatus, agentId, null, acceptedAt
        );
        boolean ok = queueRepository.compareAndSetStatus(sessionId, updated);
        if (!ok) {
            throw new SessionAlreadyAcceptedException(sessionId);
        }

        publishEvent(new SessionEvent(SessionEventType.ACCEPTED, updated));
        publishSessionAccept(sessionId, agentId, acceptedAt);
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
    public void close(String sessionId, ClosedBy closedBy) {
        String agentId = null;
        // 1. Redis 队列状态处理（状态机校验/序列化异常仅影响本步，不影响后续 DB 关闭）
        try {
            SessionQueueItem old = queueRepository.findById(sessionId).orElse(null);
            if (old != null) {
                agentId = old.agentId();
                SessionStatus newStatus = old.status().transitionTo(SessionStatus.CLOSED);
                SessionQueueItem closed = new SessionQueueItem(
                        old.sessionId(), old.userName(), old.transferReason(),
                        old.tag(), old.waitSince(), newStatus, old.agentId(),
                        null, old.acceptedAt()
                );
                publishEvent(new SessionEvent(SessionEventType.CLOSED, closed));
            } else {
                log.warn("[SessionQueue] close 时 Redis 无数据（可能已重启）仍执行 DB 关闭 sessionId={}", sessionId);
                SessionQueueItem minimal = new SessionQueueItem(
                        sessionId, "", "", "", 0L, SessionStatus.CLOSED, null);
                publishEvent(new SessionEvent(SessionEventType.CLOSED, minimal));
            }
            queueRepository.delete(sessionId); // 幂等，无数据时 no-op
        } catch (IllegalStateException e) {
            log.warn("[SessionQueue] close 状态机校验/Redis 处理异常，仍继续执行 DB 关闭 sessionId={} msg={}",
                    sessionId, e.getMessage());
        }
        // 2. DB 关闭 + CSAT 邀请：无论 Redis 处理成功与否都必须执行
        publishSessionEnd(sessionId, closedBy);
        // 通用 Webhook：会话关闭
        webhookEventPublisher.publish(WebhookScope.SESSION_CLOSED,
                WebhookEventContextFactory.buildSessionEvent(
                        WebhookScope.SESSION_CLOSED,
                        WebhookEventTypes.SESSION_CLOSED,
                        sessionId, null,
                        Map.of("closedBy", closedBy != null ? closedBy.name() : "")));
        // 同步推送 CSAT 邀请给访客 WS：必须在关闭访客连接之前完成，
        // 否则连接已从注册表移除，csat_request 帧会被丢弃（访客端收不到实时评价弹窗）。
        // I6 修复：triggerCsat 本身无 @Async 注解，此方法为同步调用，移除误导性注释。
        triggerCsat(sessionId, agentId);
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
            throw new BusinessException(400, "fromAgentId 格式非法: " + fromAgentId);
        }
        if (targetAgentId == null || !AGENT_ID_PATTERN.matcher(targetAgentId).matches()) {
            throw new BusinessException(400, "targetAgentId 格式非法: " + targetAgentId);
        }
        if (!agentRegistry.isOnline(targetAgentId)) {
            throw new BusinessException(400, "目标座席不在线: " + targetAgentId);
        }

        SessionQueueItem old = queueRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(404, "会话不存在: " + sessionId));
        if (old.status() != SessionStatus.ACTIVE) {
            throw new BusinessException(409, "只有 ACTIVE 状态的会话才能转交，当前状态: " + old.status());
        }

        SessionQueueItem transferred = new SessionQueueItem(
                old.sessionId(), old.userName(), old.transferReason(),
                old.tag(), old.waitSince(), SessionStatus.ACTIVE, targetAgentId,
                null, old.acceptedAt()
        );
        boolean ok = queueRepository.compareAndSetAgentId(sessionId, fromAgentId, transferred);
        if (!ok) {
            throw new BusinessException(409, "会话归属已变更，无法转交: " + sessionId);
        }

        publishEvent(new SessionEvent(SessionEventType.TRANSFER, transferred, fromAgentId, targetAgentId));
        publishSessionTransfer(sessionId, fromAgentId, targetAgentId, Instant.now().getEpochSecond());
        // 通用 Webhook：座席间转接
        webhookEventPublisher.publish(WebhookScope.SESSION_TRANSFERRED,
                WebhookEventContextFactory.buildSessionEvent(
                        WebhookScope.SESSION_TRANSFERRED,
                        WebhookEventTypes.SESSION_TRANSFER,
                        sessionId, null,
                        Map.of(
                                "fromAgentId", fromAgentId == null ? "" : fromAgentId,
                                "toAgentId", targetAgentId == null ? "" : targetAgentId)));
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
     * 触发 CSAT 邀请推送（同步）。在会话关闭主流程内、关闭访客 WS 之前调用，
     * 确保 csat_request 实时送达访客端。推送失败只记录日志，不影响关闭结果。
     */
    void triggerCsat(String sessionId, String agentId) {
        try {
            // 座席 ID 可能含字母/连字符，非数字时降级为 null 而非中断整个邀请，避免 CSAT 邀请丢失
            Long agentIdLong = null;
            if (agentId != null && !agentId.isBlank()) {
                try {
                    agentIdLong = Long.parseLong(agentId);
                } catch (NumberFormatException nfe) {
                    log.warn("[CSAT] 座席 ID 非数字，agentId 记为空 sessionId={} agentId={}", sessionId, agentId);
                }
            }
            CsatRatingDO csat = csatService.createInvitation(
                    sessionId, null, agentIdLong, com.aria.conversation.domain.CsatChannel.HUMAN);
            Map<String, Object> frame = new java.util.LinkedHashMap<>(
                    com.aria.conversation.application.service.support.CsatInvites.payload(csat));
            frame.put("type", ChatEvent.EventType.CSAT_REQUEST);
            visitorNotifier.notifyVisitor(sessionId, frame);
            log.info("[CSAT] 人工会话关闭触发评价邀请 sessionId={} csatId={}", sessionId, csat.getId());
        } catch (Exception e) {
            log.warn("[CSAT] 触发评价邀请失败 sessionId={}", sessionId, e);
        }
    }

    // ---- 内部：事件广播 ----

    private List<TagVO> loadVisitorTagsFromCache(String visitorId) {
        if (visitorId == null || visitorId.isBlank()) return null;
        try {
            String cached = redisTemplate.opsForValue().get(VISITOR_TAG_CACHE_PREFIX + visitorId);
            if (cached == null) return null;
            return objectMapper.readValue(cached, new TypeReference<List<TagVO>>() {});
        } catch (Exception e) {
            log.warn("[SessionQueue] failed to load visitor tags from cache for visitor={}", visitorId, e);
            return null;
        }
    }

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

    private void publishSessionEnd(String sessionId, ClosedBy closedBy) {
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
