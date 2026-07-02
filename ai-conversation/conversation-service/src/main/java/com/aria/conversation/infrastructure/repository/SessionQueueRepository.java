package com.aria.conversation.infrastructure.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.common.web.redis.RedisLockHelper;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.SessionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话队列 Redis 仓储。
 *
 * <p>封装所有对 {@code agent:session:queue} Hash 的 Redis 操作，
 * 对上层（{@link com.aria.conversation.application.service.SessionQueueService}）
 * 屏蔽 key 命名、JSON 序列化/反序列化、CAS 标记字符串等基础设施细节。
 *
 * <p>Redis 数据结构：
 * <pre>
 *   agent:session:queue  Hash  { sessionId → JSON(SessionQueueItem) }
 * </pre>
 *
 * <p>CAS 实现说明：{@link #compareAndSetStatus} 和 {@link #compareAndSetAgentId}
 * 使用 {@link RedisLockHelper#compareAndSetHashField} 进行原子字符串包含匹配，
 * 标记字符串格式由本类统一维护，调用方无需感知。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SessionQueueRepository {

    private static final String QUEUE_KEY             = "agent:session:queue";
    private static final Duration QUEUE_TTL           = Duration.ofDays(1);

    /** CAS 标记：accept 时校验当前状态为 WAITING */
    private static final String MARKER_WAITING        = "\"status\":\"WAITING\"";
    /** CAS 标记模板：transfer 时校验当前 agentId 匹配 */
    private static final String MARKER_AGENT_ID_FMT   = "\"agentId\":\"%s\"";

    private final RedisCacheHelper cache;
    private final RedisLockHelper  lockHelper;
    private final ObjectMapper     objectMapper;

    // ----------------------------------------------------------------
    // 写操作
    // ----------------------------------------------------------------

    /**
     * 保存/更新会话队列项（新入队或 CAS 失败后的回写）。
     * 每次写入刷新 Hash TTL，防止 Hash 永不过期。
     */
    public void save(SessionQueueItem item) {
        try {
            cache.hPut(QUEUE_KEY, item.sessionId(),
                    objectMapper.writeValueAsString(item), QUEUE_TTL);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("会话队列项序列化失败 sessionId=" + item.sessionId(), e);
        }
    }

    /**
     * 删除指定会话（会话关闭时调用，幂等）。
     */
    public void delete(String sessionId) {
        cache.hDelete(QUEUE_KEY, sessionId);
    }

    /**
     * 刷新 Hash 整体 TTL（CAS 成功后调用，避免长会话被驱逐）。
     */
    public void refreshTtl() {
        cache.expire(QUEUE_KEY, QUEUE_TTL);
    }

    // ----------------------------------------------------------------
    // 读操作
    // ----------------------------------------------------------------

    /**
     * 按 sessionId 查询队列项。
     */
    public Optional<SessionQueueItem> findById(String sessionId) {
        String raw = cache.hGet(QUEUE_KEY, sessionId);
        if (raw == null) return Optional.empty();
        return Optional.ofNullable(deserialize(raw));
    }

    /**
     * 按状态过滤，返回按 waitSince 升序排列的列表。
     */
    public List<SessionQueueItem> findByStatus(SessionStatus status) {
        Map<Object, Object> all = cache.hEntries(QUEUE_KEY);
        List<SessionQueueItem> result = new ArrayList<>(all.size());
        for (Object val : all.values()) {
            SessionQueueItem item = deserialize((String) val);
            if (item != null && status == item.status()) {
                result.add(item);
            }
        }
        result.sort(Comparator.comparingLong(SessionQueueItem::waitSince));
        return result;
    }

    /**
     * 返回所有队列项（用于统计座席会话数等场景）。
     */
    public List<SessionQueueItem> findAll() {
        Map<Object, Object> all = cache.hEntries(QUEUE_KEY);
        List<SessionQueueItem> result = new ArrayList<>(all.size());
        for (Object val : all.values()) {
            SessionQueueItem item = deserialize((String) val);
            if (item != null) result.add(item);
        }
        return result;
    }

    // ----------------------------------------------------------------
    // CAS 操作
    // ----------------------------------------------------------------

    /**
     * 原子状态变更：仅当当前状态为 WAITING 时，将会话更新为新值。
     * 用于座席接入（WAITING → ACTIVE），防止两名座席并发抢接同一会话。
     *
     * @param sessionId  目标会话 ID
     * @param newItem    更新后的队列项（含新状态和 agentId）
     * @return true 表示 CAS 成功，false 表示已被其他座席抢接
     */
    public boolean compareAndSetStatus(String sessionId, SessionQueueItem newItem) {
        try {
            String newJson = objectMapper.writeValueAsString(newItem);
            boolean ok = lockHelper.compareAndSetHashField(
                    QUEUE_KEY, sessionId, MARKER_WAITING, newJson);
            if (ok) refreshTtl();
            return ok;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("CAS 序列化失败 sessionId=" + sessionId, e);
        }
    }

    /**
     * 原子归属变更：仅当当前 agentId 与 fromAgentId 匹配时，更新为新值。
     * 用于会话转交，防止并发转交时归属错乱。
     *
     * @param sessionId   目标会话 ID
     * @param fromAgentId 期望的当前座席 ID
     * @param newItem     更新后的队列项（含新 agentId）
     * @return true 表示 CAS 成功，false 表示归属已变更
     */
    public boolean compareAndSetAgentId(String sessionId, String fromAgentId, SessionQueueItem newItem) {
        try {
            String marker  = String.format(MARKER_AGENT_ID_FMT, fromAgentId);
            String newJson = objectMapper.writeValueAsString(newItem);
            boolean ok = lockHelper.compareAndSetHashField(
                    QUEUE_KEY, sessionId, marker, newJson);
            if (ok) refreshTtl();
            return ok;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("CAS 序列化失败 sessionId=" + sessionId, e);
        }
    }

    // ----------------------------------------------------------------
    // 内部工具
    // ----------------------------------------------------------------

    private SessionQueueItem deserialize(String raw) {
        try {
            return objectMapper.readValue(raw, SessionQueueItem.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[SessionQueueRepo] 反序列化失败，跳过 raw={}", raw, e);
            return null;
        }
    }
}
