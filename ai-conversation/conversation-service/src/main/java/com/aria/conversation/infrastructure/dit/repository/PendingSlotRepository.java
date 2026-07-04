package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 槽位解析挂起状态 Redis 仓储。
 *
 * <p>key: {@code dit:pending:{sessionId}}，TTL 30 分钟。
 * 30 分钟内用户无响应，状态自动过期，下次对话从头开始 pipeline。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PendingSlotRepository {

    private static final String KEY_PREFIX = "dit:pending:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedisCacheHelper cache;
    private final ObjectMapper objectMapper;

    /**
     * 保存挂起状态。
     *
     * @param state 挂起状态
     */
    public void save(PendingSlotState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            cache.set(KEY_PREFIX + state.getSessionId(), json, TTL);
            log.debug("[DIT] 保存挂起状态 sessionId={} slot={} type={} retry={}",
                    state.getSessionId(), state.getPendingSlot(), state.getPendingType(), state.getRetryCount());
        } catch (Exception e) {
            log.error("[DIT] 保存挂起状态失败 sessionId={}", state.getSessionId(), e);
        }
    }

    /**
     * 查询挂起状态。
     *
     * @param sessionId 会话 ID
     * @return 挂起状态，不存在或已过期返回 empty
     */
    public Optional<PendingSlotState> find(String sessionId) {
        String json = cache.get(KEY_PREFIX + sessionId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, PendingSlotState.class));
        } catch (Exception e) {
            log.warn("[DIT] 挂起状态反序列化失败 sessionId={}", sessionId, e);
            delete(sessionId);
            return Optional.empty();
        }
    }

    /**
     * 删除挂起状态（槽位解析完成或触发兜底转人工时调用）。
     *
     * @param sessionId 会话 ID
     */
    public void delete(String sessionId) {
        cache.delete(KEY_PREFIX + sessionId);
        log.debug("[DIT] 删除挂起状态 sessionId={}", sessionId);
    }

    /**
     * 判断会话是否有挂起中的槽位解析。
     *
     * @param sessionId 会话 ID
     */
    public boolean hasPending(String sessionId) {
        return find(sessionId).isPresent();
    }
}
