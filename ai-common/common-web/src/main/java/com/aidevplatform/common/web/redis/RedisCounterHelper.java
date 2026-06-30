package com.aidevplatform.common.web.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * Redis 计数器与频率限制封装（职责：计数）。
 *
 * <p>提供"原子计数 / 频率窗口 / 封禁"三类能力，统一替代业务侧散落的
 * {@code opsForValue().increment / setIfAbsent / expire} 组合调用。
 *
 * <p>典型用法：
 * <ul>
 *   <li>登录限流：{@link #firstAccess} 判定窗口首次访问 + {@link #increment} 累计</li>
 *   <li>错误次数：{@link #increment} 累计错误，达到阈值后 {@link #ban} 锁定</li>
 *   <li>短信防重发：{@link #firstAccess} 60s 内只允许一次</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCounterHelper {

    /**
     * 原子 INCR + 首次 EXPIRE 的 Lua 脚本。
     *
     * <p>当返回值 == 1 时（即首次写入）才设置 TTL，保证滑动窗口/计数语义正确，
     * 同时避免 Java 端 INCR-EXPIRE 两次 RTT 之间崩溃导致的 key 永久驻留。
     */
    private static final String INCR_WITH_TTL_LUA =
            "local v = redis.call('INCR', KEYS[1])\n" +
            "if v == 1 then\n" +
            "    redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
            "end\n" +
            "return v";

    private static final RedisScript<Long> INCR_WITH_TTL_SCRIPT =
            new DefaultRedisScript<>(INCR_WITH_TTL_LUA, Long.class);

    private final StringRedisTemplate redis;

    /**
     * 原子 INCR 并在首次写入时设置 TTL（Lua 脚本保证原子性）。
     *
     * <p>实现要点（避免双 RTT 竞态）：
     * <ul>
     *   <li>整个 INCR + EXPIRE 在 Redis 服务端单线程内完成，不会出现
     *       "INCR 已写入但 EXPIRE 未执行导致 key 永久驻留" 的故障窗口</li>
     *   <li>当 INCR 返回 1 时（即首次写入）才设置 TTL，后续 INCR 不刷新 TTL，
     *       保证窗口语义（滑动窗口/封禁累计）</li>
     * </ul>
     *
     * @param key 计数器 key
     * @param ttl 首次写入时的过期时间
     * @return 累计后的计数值，Lua 异常时返回 0
     */
    public long increment(String key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("TTL 必须大于零 key=" + key);
        }
        Long count = redis.execute(
                INCR_WITH_TTL_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(ttl.getSeconds())
        );
        return count != null ? count : 0L;
    }

    /**
     * 获取计数值，不存在或非数字返回 0。
     *
     * @param key 计数器 key
     * @return 当前计数值
     */
    public long get(String key) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("[RedisCounter] 计数值非数字 key={} value={}", key, raw);
            return 0L;
        }
    }

    /**
     * 频率限制窗口判定：若 key 在窗口内首次出现，返回 true 并设置 TTL；
     * 否则返回 false（窗口内重复访问）。
     *
     * <p>典型用法：短信防重发（60s 内只允许一次）：
     * <pre>
     * if (!counter.firstAccess("sms:rate:" + phone, Duration.ofSeconds(60))) {
     *     throw new BusinessException(429, "发送过于频繁");
     * }
     * </pre>
     *
     * @param key    频率窗口 key
     * @param window 窗口时长
     * @return true=首次访问；false=窗口内已访问过
     */
    public boolean firstAccess(String key, Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("窗口时长必须大于零 key=" + key);
        }
        Boolean ok = redis.opsForValue().setIfAbsent(key, "1", window);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 封禁指定 key（写入哨兵值），用于"错误次数达上限后锁定一段时间"场景。
     *
     * @param key      封禁 key
     * @param duration 封禁时长
     */
    public void ban(String key, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("封禁时长必须大于零 key=" + key);
        }
        redis.opsForValue().set(key, "1", duration);
    }

    /**
     * 检查是否处于封禁状态。
     *
     * @param key 封禁 key
     * @return true=已封禁
     */
    public boolean isBanned(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /**
     * 重置计数（删除 key）。
     *
     * @param key 计数器/窗口/封禁 key
     */
    public void reset(String key) {
        redis.delete(key);
    }
}
