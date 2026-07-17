package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.application.dto.InitSessionResult;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 访客会话初始化服务。
 *
 * <p>提供统一的"查询或创建"入口：同一 anonymousId 若有非 CLOSED 会话则直接返回，
 * 否则创建新的 AI_CHAT 会话。通过 Redisson 分布式锁防止并发重复创建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitorSessionService {

    /** anonymousId 校验正则：至少含一个 _ 或 - 的字母数字下划线连字符串，总长 2-64 位 */
    private static final Pattern ANONYMOUS_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_\\-]{8,64}$");

    private static final String GUEST_SESSION_PREFIX = "guest-";
    private static final String LOCK_KEY_PREFIX      = "visitor:init:";
    private static final long   LOCK_TTL_SECONDS     = 3L;
    private static final long   LOCK_WAIT_SECONDS    = 2L;
    private static final String DEFAULT_VISITOR_NAME = "访客";

    private final ConversationPersistRepository persistRepository;
    private final RedissonClient                redissonClient;

    /**
     * 获取或创建访客会话（幂等，分布式锁保护）。
     *
     * <p>流程：校验 anonymousId → 加锁 → 查活跃会话 → 有则返回，无则新建。
     *
     * @param anonymousId   前端 localStorage UUID，格式 {@code ^[a-zA-Z0-9_\-]{8,64}$}
     * @param visitorName   访客展示名，null 时默认 "访客"
     * @param visitorIp     客户端 IP（X-Forwarded-For 首个或 RemoteAddr），可为 null
     * @param visitorDevice 原始 User-Agent 字符串，可为 null
     * @return 会话初始化结果，包含 sessionId、当前状态和是否新建标志
     * @throws BusinessException anonymousId 格式非法时抛出
     */
    public InitSessionResult getOrCreate(String anonymousId,
                                          String visitorName,
                                          String visitorIp,
                                          String visitorDevice) {
        validateAnonymousId(anonymousId);

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + anonymousId);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "系统繁忙，请稍后重试");
        }
        if (!acquired) {
            throw new BusinessException(503, "请求频繁，请稍后重试");
        }
        try {
            Optional<ConversationEntity> active = persistRepository.findActiveByVisitorId(anonymousId);
            if (active.isPresent()) {
                ConversationEntity e = active.get();
                log.debug("[VisitorSession] 恢复已有会话 anonymousId={} sessionId={} status={}",
                        anonymousId, e.getSessionId(), e.getStatus());
                return new InitSessionResult(e.getSessionId(), e.getStatus(), false);
            }

            String sessionId = GUEST_SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
            String name = (visitorName != null && !visitorName.isBlank())
                    ? visitorName : DEFAULT_VISITOR_NAME;
            persistRepository.createAiChatSession(
                    sessionId, anonymousId, name, visitorIp, visitorDevice, OffsetDateTime.now());
            log.info("[VisitorSession] 新建会话 anonymousId={} sessionId={}", anonymousId, sessionId);
            return new InitSessionResult(sessionId, SessionStatus.AI_CHAT, true);
        } finally {
            lock.unlock();
        }
    }

    private void validateAnonymousId(String anonymousId) {
        if (anonymousId == null || !ANONYMOUS_ID_PATTERN.matcher(anonymousId).matches()) {
            throw new BusinessException(400, "X-Anonymous-Id 格式非法，需满足 ^[a-zA-Z0-9_\\-]{8,64}$");
        }
    }
}
