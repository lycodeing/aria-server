package com.aria.conversation.application.service;

import com.aria.common.web.redis.RedisCacheHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 补偿日志服务。
 *
 * <p>统一管理补偿日志写入（Redis List），供 {@code DomainSessionAppService} 和
 * {@code SessionQueueService} 在审计/持久化失败时委托，避免在多个 Service 中散落
 * Redis 操作和 JSON 序列化逻辑。
 *
 * <p>Redis 操作统一通过 {@link RedisCacheHelper} 封装，遵循项目 Redis 工具类规范。
 * 后续可由定时任务读取补偿日志并重试写入审计表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationLogService {

    private final RedisCacheHelper cache;
    private final ObjectMapper objectMapper;

    private static final String DOMAIN_SWITCH_KEY = "chat:compensation:domain_switch";
    private static final Duration TTL = Duration.ofDays(7);
    /** 补偿日志列表最大保留条数，超出时自动丢弃最旧的记录 */
    private static final long MAX_ENTRIES = 1000L;

    /**
     * 记录域切换审计失败的补偿日志（Redis List，TTL 7 天）。
     *
     * @param sessionId   会话 ID
     * @param fromDomain  源域 code
     * @param toDomain    目标域 code
     * @param switchType  切换类型（INITIAL / ROUTER_MODEL / LLM_TOOL）
     * @param userMessage 用户消息
     * @param reason      切换原因
     */
    public void logDomainSwitch(String sessionId, String fromDomain, String toDomain,
                                 String switchType, String userMessage, String reason) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "sessionId", safe(sessionId),
                    "from", safe(fromDomain),
                    "to", safe(toDomain),
                    "switchType", safe(switchType),
                    "msg", safe(userMessage),
                    "reason", safe(reason),
                    "ts", Instant.now().getEpochSecond()));
            // I1 修复：lTrim 限制列表最大长度，防止审计失败频繁时 list 无限增长撑爆 Redis；
            // TTL 仅在 key 首次创建时设置，不每次刷新（每次刷新会导致补偿日志永不过期）
            boolean isNew = !cache.exists(DOMAIN_SWITCH_KEY);
            cache.lRightPush(DOMAIN_SWITCH_KEY, payload);
            cache.lTrim(DOMAIN_SWITCH_KEY, -MAX_ENTRIES, -1); // 保留最近 MAX_ENTRIES 条
            if (isNew) {
                cache.expire(DOMAIN_SWITCH_KEY, TTL);
            }
        } catch (Exception ex) {
            log.error("[Compensation] 域切换补偿日志写入失败 sessionId={}", sessionId, ex);
        }
    }

    /** null 安全的字符串转换（M1 修复：消除 7 行三元表达式重复） */
    private static String safe(Object value) {
        return value != null ? value.toString() : "";
    }
}
