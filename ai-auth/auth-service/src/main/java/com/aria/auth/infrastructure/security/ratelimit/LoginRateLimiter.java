package com.aria.auth.infrastructure.security.ratelimit;

import com.aria.common.web.redis.RedisLockHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 登录 IP 频率限制器。
 *
 * <p>基于 {@link RedisLockHelper}：同一 IP 每分钟最多 {@value #MAX_PER_MINUTE} 次登录尝试，
 * 失败累计达到 {@value #IP_BAN_THRESHOLD} 次时封禁 IP {@code BAN_DURATION} 时长。
 *
 * <p>原子化：ban 检查和 increment 通过单个 Lua 脚本合并为一次 Redis 操作，
 * 消除"先检查后计数"的 TOCTOU 竞态，防止高并发时超频请求漏过限制。
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final int MAX_PER_MINUTE = 10;
    private static final int IP_BAN_THRESHOLD = 20;
    private static final Duration BAN_DURATION = Duration.ofMinutes(5);
    private static final Duration RATE_WINDOW = Duration.ofSeconds(60);
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(10);

    private static final String KEY_BAN_PREFIX = "auth:ban:";
    private static final String KEY_RATE_PREFIX = "auth:rl:";
    private static final String KEY_FAIL_PREFIX = "auth:fail:";

    /**
     * 原子限流 Lua 脚本。
     * <ol>
     *   <li>先检查 ban key 是否存在（EXISTS），存在则直接返回 0（拒绝）</li>
     *   <li>对 rate key 执行 INCR，首次写入时设置 TTL</li>
     *   <li>若计数超过阈值，写入 ban key 并返回 0（拒绝）</li>
     *   <li>否则返回 1（允许）</li>
     * </ol>
     * KEYS[1]=banKey, KEYS[2]=rateKey
     * ARGV[1]=maxPerMinute, ARGV[2]=rateTtlSec, ARGV[3]=banTtlSec
     */
    private static final String TRY_ACQUIRE_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            local v = redis.call('INCR', KEYS[2])
            if v == 1 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end
            if v > tonumber(ARGV[1]) then
                redis.call('SET', KEYS[1], '1', 'EX', ARGV[3])
                return 0
            end
            return 1
            """;

    private static final RedisScript<Long> TRY_ACQUIRE_SCRIPT =
            new DefaultRedisScript<>(TRY_ACQUIRE_LUA, Long.class);

    private final RedisLockHelper lockHelper;

    /**
     * 尝试获取访问许可（原子操作，消除 ban 检查与 increment 之间的竞态）。
     *
     * @param ip 客户端 IP
     * @return true=允许；false=已封禁或超频
     */
    public boolean tryAcquire(String ip) {
        Long result = lockHelper.executeLua(
                TRY_ACQUIRE_SCRIPT,
                List.of(KEY_BAN_PREFIX + ip, KEY_RATE_PREFIX + ip),
                String.valueOf(MAX_PER_MINUTE),
                String.valueOf(RATE_WINDOW.getSeconds()),
                String.valueOf(BAN_DURATION.getSeconds())
        );
        return result != null && result == 1L;
    }

    /**
     * 记录登录失败，累计达阈值后触发 IP 封禁。
     *
     * @param ip 客户端 IP
     */
    public void recordFailure(String ip) {
        String failKey = KEY_FAIL_PREFIX + ip;
        // RedisCounterHelper 的 increment 已是 Lua 原子操作（INCR + 首次 EXPIRE）
        // 此处通过 Lua 脚本直接执行，避免依赖多个 Helper 实例
        String banLua = """
                local v = redis.call('INCR', KEYS[1])
                if v == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
                if v >= tonumber(ARGV[2]) then
                    redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
                end
                return v
                """;
        lockHelper.executeLua(
                new DefaultRedisScript<>(banLua, Long.class),
                List.of(failKey, KEY_BAN_PREFIX + ip),
                String.valueOf(FAIL_WINDOW.getSeconds()),
                String.valueOf(IP_BAN_THRESHOLD),
                String.valueOf(BAN_DURATION.getSeconds())
        );
    }
}
