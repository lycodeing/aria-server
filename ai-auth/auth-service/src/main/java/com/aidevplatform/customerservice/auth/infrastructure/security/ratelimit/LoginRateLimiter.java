package com.aidevplatform.customerservice.auth.infrastructure.security.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录 IP 频率限制器。
 * <p>基于 Redis 滑动窗口：同一 IP 每分钟最多 N 次登录尝试。
 * 超过阈值后临时封禁 IP。
 */
@Component
public class LoginRateLimiter {

    private final StringRedisTemplate redis;
    private static final int MAX_PER_MINUTE = 10;
    private static final int IP_BAN_THRESHOLD = 20;
    private static final Duration BAN_DURATION = Duration.ofMinutes(5);

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试获取许可。
     *
     * @return true 允许，false 被限流
     */
    public boolean tryAcquire(String ip) {
        // 检查是否已被封禁
        String banKey = "auth:ban:" + ip;
        if (Boolean.TRUE.equals(redis.hasKey(banKey))) {
            return false;
        }

        // 滑动窗口计数
        String rateKey = "auth:rl:" + ip;
        Long count = redis.opsForValue().increment(rateKey);
        if (count != null && count == 1) {
            redis.expire(rateKey, Duration.ofSeconds(60));
        }

        if (count != null && count > MAX_PER_MINUTE) {
            // 触发 IP 封禁
            redis.opsForValue().set(banKey, "1", BAN_DURATION);
            return false;
        }

        return true;
    }

    /**
     * 记录失败（用于触发 IP 封禁阈值）。
     */
    public void recordFailure(String ip) {
        String failKey = "auth:fail:" + ip;
        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1) {
            redis.expire(failKey, Duration.ofMinutes(10));
        }
        if (count != null && count >= IP_BAN_THRESHOLD) {
            redis.opsForValue().set("auth:ban:" + ip, "1", BAN_DURATION);
        }
    }
}
