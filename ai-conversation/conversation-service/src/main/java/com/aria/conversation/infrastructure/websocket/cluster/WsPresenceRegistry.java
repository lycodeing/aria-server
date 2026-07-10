package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.conversation.infrastructure.cache.ConversationCacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

/**
 * WS 集群 presence 注册表。
 *
 * <p>记录访客/座席 WebSocket 连接所在的 Pod（podId = RabbitMQ AnonymousQueue 名称）：
 * <ul>
 *   <li>访客：{@code ws:visitor:pod:{sessionId}} → podId（String，单值）</li>
 *   <li>座席：{@code ws:agent:pods:{agentId}} → Set&lt;podId&gt;（支持多端 BROADCAST 模式）</li>
 * </ul>
 *
 * <p>访客操作使用 {@link RedisCacheHelper}（String 类型），单命令原子写入，强制 TTL。
 * 座席操作使用 {@link StringRedisTemplate}（Set 类型），{@link RedisCacheHelper} 未封装 Set。
 *
 * <p>⚠️ {@link #registerAgent} 的 SADD + EXPIRE 两步非原子：极端情况下 Pod 崩溃于两步之间
 * 会导致 key 无 TTL 永久驻留；建议未来用 Lua 脚本合并为原子操作。
 * 心跳每 30s 刷新 TTL（TTL=90s），可兜底大多数场景。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsPresenceRegistry {

    private static final Duration TTL = Duration.ofSeconds(90);

    private final RedisCacheHelper    cache;
    private final StringRedisTemplate redis;

    // ---- 访客 presence ----

    /**
     * 访客连接建立：记录 sessionId → podId（单命令原子，强制 TTL）。
     *
     * @param sessionId 访客会话 ID
     * @param podId     当前 Pod 标识（RabbitMQ AnonymousQueue 名称）
     */
    public void registerVisitor(String sessionId, String podId) {
        cache.set(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId, podId, TTL);
    }

    /**
     * 访客连接断开：删除 presence。
     *
     * @param sessionId 访客会话 ID
     */
    public void unregisterVisitor(String sessionId) {
        cache.delete(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId);
    }

    /**
     * 查询访客所在 podId；不在线返回 {@code null}。
     *
     * @param sessionId 访客会话 ID
     * @return podId 或 {@code null}
     */
    public String getVisitorPod(String sessionId) {
        return cache.get(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId);
    }

    /**
     * 刷新访客 presence TTL（心跳调用）。
     *
     * @param sessionId 访客会话 ID
     */
    public void refreshVisitor(String sessionId) {
        cache.expire(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId, TTL);
    }

    // ---- 座席 presence ----

    /**
     * 座席连接建立：将 podId 加入 agentId 的 podId 集合并刷新 TTL。
     *
     * <p>⚠️ SADD 与 EXPIRE 两步非原子，极端场景下可能导致 key 无 TTL 永久驻留。
     *
     * @param agentId 座席 ID
     * @param podId   当前 Pod 标识
     */
    public void registerAgent(String agentId, String podId) {
        redis.opsForSet().add(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, podId);
        cache.expire(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, TTL);
    }

    /**
     * 座席连接断开：从集合中移除 podId。
     *
     * @param agentId 座席 ID
     * @param podId   当前 Pod 标识
     */
    public void unregisterAgent(String agentId, String podId) {
        redis.opsForSet().remove(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, podId);
    }

    /**
     * 查询座席所在的所有 podId；不在线返回空集合（不返回 {@code null}）。
     *
     * @param agentId 座席 ID
     * @return podId 集合，从不为 {@code null}
     */
    public Set<String> getAgentPods(String agentId) {
        Set<String> members = redis.opsForSet().members(
                ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId);
        return members != null ? members : Collections.emptySet();
    }

    /**
     * 刷新座席 presence TTL（心跳调用）。
     *
     * @param agentId 座席 ID
     */
    public void refreshAgent(String agentId) {
        cache.expire(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, TTL);
    }

    /**
     * 清理已下线 Pod 的所有 presence 数据（Pod 崩溃即时清理）。
     *
     * <p>由 {@code RabbitMQConfig} 的 {@code ReturnsCallback} 在收到
     * {@code ws.delivery} NO_ROUTE 响应时调用，将推送中断窗口从 TTL 90s 压缩到接近 0。
     *
     * <p>使用 SCAN（非 KEYS）遍历，避免阻塞 Redis。
     *
     * @param deadPodId 已下线 Pod 的 podId
     */
    public void removeStalePod(String deadPodId) {
        int agentCleaned = 0;
        int visitorCleaned = 0;

        // 1. 从所有座席 presence Set 中移除死 Pod
        ScanOptions agentOpts = ScanOptions.scanOptions()
                .match(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + "*").count(200).build();
        try (Cursor<String> cursor = redis.scan(agentOpts)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long removed = redis.opsForSet().remove(key, deadPodId);
                if (removed != null && removed > 0) agentCleaned++;
            }
        } catch (Exception e) {
            log.warn("[PresenceRegistry] 清理座席 presence 异常 deadPodId={} msg={}", deadPodId, e.getMessage());
        }

        // 2. 删除指向死 Pod 的访客 presence key
        ScanOptions visitorOpts = ScanOptions.scanOptions()
                .match(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + "*").count(200).build();
        try (Cursor<String> cursor = redis.scan(visitorOpts)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String value = cache.get(key);
                if (deadPodId.equals(value)) {
                    cache.delete(key);
                    visitorCleaned++;
                }
            }
        } catch (Exception e) {
            log.warn("[PresenceRegistry] 清理访客 presence 异常 deadPodId={} msg={}", deadPodId, e.getMessage());
        }

        log.info("[PresenceRegistry] 死 Pod presence 已清理 deadPodId={} agents={} visitors={}",
                deadPodId, agentCleaned, visitorCleaned);
    }
}


/**
 * WS 集群 presence 注册表。
 *
 * <p>记录访客/座席 WebSocket 连接所在的 Pod（podId = RabbitMQ AnonymousQueue 名称）：
 * <ul>
 *   <li>访客：{@code ws:visitor:pod:{sessionId}} → podId（String，单值）</li>
 *   <li>座席：{@code ws:agent:pods:{agentId}} → Set&lt;podId&gt;（支持多端 BROADCAST 模式）</li>
 * </ul>
 *
 * <p>访客操作使用 {@link RedisCacheHelper}（String 类型），单命令原子写入，强制 TTL。
 * 座席操作使用 {@link StringRedisTemplate}（Set 类型），{@link RedisCacheHelper} 未封装 Set。
 *
 * <p>⚠️ {@link #registerAgent} 的 SADD + EXPIRE 两步非原子：极端情况下 Pod 崩溃于两步之间
 * 会导致 key 无 TTL 永久驻留；建议未来用 Lua 脚本合并为原子操作。
 * 心跳每 30s 刷新 TTL（TTL=90s），可兜底大多数场景。
 */
@Component
@RequiredArgsConstructor
public class WsPresenceRegistry {

    private static final Duration TTL = Duration.ofSeconds(90);

    private final RedisCacheHelper    cache;
    private final StringRedisTemplate redis;

    // ---- 访客 presence ----

    /**
     * 访客连接建立：记录 sessionId → podId（单命令原子，强制 TTL）。
     *
     * @param sessionId 访客会话 ID
     * @param podId     当前 Pod 标识（RabbitMQ AnonymousQueue 名称）
     */
    public void registerVisitor(String sessionId, String podId) {
        cache.set(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId, podId, TTL);
    }

    /**
     * 访客连接断开：删除 presence。
     *
     * @param sessionId 访客会话 ID
     */
    public void unregisterVisitor(String sessionId) {
        cache.delete(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId);
    }

    /**
     * 查询访客所在 podId；不在线返回 {@code null}。
     *
     * @param sessionId 访客会话 ID
     * @return podId 或 {@code null}
     */
    public String getVisitorPod(String sessionId) {
        return cache.get(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId);
    }

    /**
     * 刷新访客 presence TTL（心跳调用）。
     *
     * @param sessionId 访客会话 ID
     */
    public void refreshVisitor(String sessionId) {
        cache.expire(ConversationCacheKeys.WS_VISITOR_POD_PREFIX + sessionId, TTL);
    }

    // ---- 座席 presence ----

    /**
     * 座席连接建立：将 podId 加入 agentId 的 podId 集合并刷新 TTL。
     *
     * <p>⚠️ SADD 与 EXPIRE 两步非原子，极端场景下可能导致 key 无 TTL 永久驻留。
     *
     * @param agentId 座席 ID
     * @param podId   当前 Pod 标识
     */
    public void registerAgent(String agentId, String podId) {
        redis.opsForSet().add(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, podId);
        cache.expire(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, TTL);
    }

    /**
     * 座席连接断开：从集合中移除 podId。
     *
     * @param agentId 座席 ID
     * @param podId   当前 Pod 标识
     */
    public void unregisterAgent(String agentId, String podId) {
        redis.opsForSet().remove(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, podId);
    }

    /**
     * 查询座席所在的所有 podId；不在线返回空集合（不返回 {@code null}）。
     *
     * @param agentId 座席 ID
     * @return podId 集合，从不为 {@code null}
     */
    public Set<String> getAgentPods(String agentId) {
        Set<String> members = redis.opsForSet().members(
                ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId);
        return members != null ? members : Collections.emptySet();
    }

    /**
     * 刷新座席 presence TTL（心跳调用）。
     *
     * @param agentId 座席 ID
     */
    public void refreshAgent(String agentId) {
        cache.expire(ConversationCacheKeys.WS_AGENT_PODS_PREFIX + agentId, TTL);
    }
}
