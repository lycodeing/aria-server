package com.aria.conversation.infrastructure.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 座席在线状态注册表。
 *
 * <p>封装所有与座席在线状态相关的 Redis 操作，对上层（{@link com.aria.conversation.application.service.SessionQueueService}）
 * 屏蔽 Redis key 命名、数据结构（Hash / 计数器）等基础设施细节，
 * 业务层只感知"座席上线/下线/查询"语义。
 *
 * <p>Redis 数据结构：
 * <pre>
 *   agent:online        Hash  { agentId → JSON{name, connectedAt} }  — 座席元数据
 *   agent:online:count  Hash  { agentId → 连接数 }                   — SSE 引用计数
 * </pre>
 *
 * <p>引用计数设计：同一座席可从多个浏览器标签/设备同时建立 SSE 连接，
 * 每次建连 +1，断连 -1，计数归 0 时才真正从在线列表移除，
 * 避免关闭一个标签就把整个座席注销的问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOnlineRegistry {

    /** 座席元数据 Hash key：{agentId → JSON{name, connectedAt}} */
    private static final String AGENTS_KEY = "agent:online";
    /** 座席连接引用计数 Hash key：{agentId → 连接数} */
    private static final String COUNT_KEY  = "agent:online:count";
    /** TTL：异常断连时由过期兜底清理，防止僵尸数据永久驻留 */
    private static final Duration TTL = Duration.ofHours(12);

    private final RedisCacheHelper cache;
    private final ObjectMapper     objectMapper;

    // ----------------------------------------------------------------
    // 写操作
    // ----------------------------------------------------------------

    /**
     * 注册座席上线（SSE 连接建立时调用）。
     * 引用计数 +1；首次注册时写入元数据，已在线时仅刷新计数。
     *
     * @param agentId     座席唯一标识
     * @param displayName 座席显示名称
     */
    public void register(String agentId, String displayName) {
        try {
            // 写入/刷新元数据
            String meta = objectMapper.writeValueAsString(
                    Map.of("name", displayName, "connectedAt", Instant.now().getEpochSecond()));
            cache.hPut(AGENTS_KEY, agentId, meta, TTL);
            // 引用计数 +1
            cache.hIncrementBy(COUNT_KEY, agentId, 1);
            cache.expire(COUNT_KEY, TTL);
            log.debug("[AgentRegistry] 座席上线 agentId={} name={}", agentId, displayName);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[AgentRegistry] 注册座席序列化失败 agentId={}", agentId, e);
        }
    }

    /**
     * 注销座席一路 SSE 连接（SSE 断开时调用）。
     * 引用计数 -1，归零后才从在线列表移除（支持多标签同时在线）。
     *
     * @param agentId 座席唯一标识
     */
    public void deregister(String agentId) {
        Long remaining = cache.hIncrementBy(COUNT_KEY, agentId, -1);
        if (remaining == null || remaining <= 0) {
            // 所有连接均已断开，清理元数据和计数
            cache.hDelete(AGENTS_KEY, agentId);
            cache.hDelete(COUNT_KEY,  agentId);
            log.debug("[AgentRegistry] 座席下线（所有连接断开）agentId={}", agentId);
        } else {
            log.debug("[AgentRegistry] 座席减少一路连接 agentId={} 剩余连接数={}", agentId, remaining);
        }
    }

    // ----------------------------------------------------------------
    // 读操作
    // ----------------------------------------------------------------

    /**
     * 判断座席是否在线（至少一路 SSE 连接存活）。
     *
     * @param agentId 座席唯一标识
     * @return true 表示在线
     */
    public boolean isOnline(String agentId) {
        return cache.hHasKey(AGENTS_KEY, agentId);
    }

    /**
     * 获取所有在线座席信息列表。
     *
     * @return 在线座席列表（含 agentId、displayName、connectedAt）
     */
    public List<AgentInfo> findAll() {
        Map<Object, Object> agentMap = cache.hEntries(AGENTS_KEY);
        if (agentMap.isEmpty()) return List.of();

        List<AgentInfo> result = new ArrayList<>(agentMap.size());
        agentMap.forEach((agentId, agentJson) -> {
            try {
                Map<?, ?> info = objectMapper.readValue((String) agentJson, Map.class);
                String name = (String) info.get("name");
                Object connectedAtObj = info.get("connectedAt");
                long connectedAt = connectedAtObj instanceof Number n ? n.longValue() : 0L;
                result.add(new AgentInfo((String) agentId, name, connectedAt));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("[AgentRegistry] 解析在线座席失败 agentId={}", agentId, e);
            }
        });
        return result;
    }

    // ----------------------------------------------------------------
    // VO
    // ----------------------------------------------------------------

    /**
     * 在线座席信息。
     *
     * @param agentId     座席唯一标识
     * @param name        座席显示名称
     * @param connectedAt 最近一次上线时间（epoch seconds）
     */
    public record AgentInfo(String agentId, String name, long connectedAt) {}
}
