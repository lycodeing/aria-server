package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.Optional;

/**
 * 会话当前激活域缓存（Redis）。
 * key: {@code dit:session_domain:{sessionId}}，TTL 2 小时。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SessionDomainRepository {
    private static final String KEY_PREFIX = "dit:session_domain:";
    private static final Duration TTL = Duration.ofHours(2);
    private final RedisCacheHelper cache;

    public void save(String sessionId, String domainCode) {
        cache.set(KEY_PREFIX + sessionId, domainCode, TTL);
        log.debug("[Domain] 更新 session 激活域 sessionId={} domain={}", sessionId, domainCode);
    }

    public Optional<String> find(String sessionId) {
        return Optional.ofNullable(cache.get(KEY_PREFIX + sessionId));
    }

    public void delete(String sessionId) {
        cache.delete(KEY_PREFIX + sessionId);
    }
}
