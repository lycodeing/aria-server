package com.aria.customerservice.auth.infrastructure.security.ratelimit;

import com.aria.common.web.redis.RedisCounterHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录 IP 频率限制器。
 *
 * <p>基于 {@link RedisCounterHelper}：同一 IP 每分钟最多 {@value #MAX_PER_MINUTE} 次登录尝试，
 * 失败累计达到 {@value #IP_BAN_THRESHOLD} 次时封禁 IP {@code BAN_DURATION} 时长。
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final int      MAX_PER_MINUTE    = 10;
    private static final int      IP_BAN_THRESHOLD  = 20;
    private static final Duration BAN_DURATION      = Duration.ofMinutes(5);
    private static final Duration RATE_WINDOW       = Duration.ofSeconds(60);
    private static final Duration FAIL_WINDOW       = Duration.ofMinutes(10);

    private static final String KEY_BAN_PREFIX  = "auth:ban:";
    private static final String KEY_RATE_PREFIX = "auth:rl:";
    private static final String KEY_FAIL_PREFIX = "auth:fail:";

    private final RedisCounterHelper counter;

    /**
     * 尝试获取访问许可。
     *
     * @param ip 客户端 IP
     * @return true=允许；false=已封禁或超频
     */
    public boolean tryAcquire(String ip) {
        String banKey = KEY_BAN_PREFIX + ip;
        if (counter.isBanned(banKey)) {
            return false;
        }
        long count = counter.increment(KEY_RATE_PREFIX + ip, RATE_WINDOW);
        if (count > MAX_PER_MINUTE) {
            counter.ban(banKey, BAN_DURATION);
            return false;
        }
        return true;
    }

    /**
     * 记录登录失败，累计达阈值后触发 IP 封禁。
     *
     * @param ip 客户端 IP
     */
    public void recordFailure(String ip) {
        long fails = counter.increment(KEY_FAIL_PREFIX + ip, FAIL_WINDOW);
        if (fails >= IP_BAN_THRESHOLD) {
            counter.ban(KEY_BAN_PREFIX + ip, BAN_DURATION);
        }
    }
}
