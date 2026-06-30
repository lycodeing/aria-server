package com.aidevplatform.common.web.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Redis 分布式锁与原子操作封装（职责：锁）。
 *
 * <p>提供两类能力：
 * <ol>
 *   <li>分布式锁：{@link #tryLock} / {@link #unlock}，基于 SETNX + Lua CAS 释放</li>
 *   <li>原子 CAS：{@link #compareAndSetHashField} 用于 Hash 字段的"读-校验-写"原子化，
 *       业务侧不再需要手写 Lua 脚本（如 SessionQueueService 的 accept/transfer 场景）</li>
 *   <li>通用 Lua：{@link #executeLua} 暴露底层执行，业务可注入自定义脚本</li>
 * </ol>
 *
 * <p>能力边界（轻量实现，不支持以下高级特性，需更高保障请引入 Redisson）：
 * <ul>
 *   <li><b>非可重入</b>：同一线程二次 tryLock 同一 key 会立即失败</li>
 *   <li><b>不自动续期</b>：业务持锁时间不得超过 TTL，否则锁过期被他人抢占</li>
 *   <li><b>非公平锁</b>：竞争失败后调用方自己决定退避/重试策略</li>
 * </ul>
 *
 * <p>所有锁必须设置 TTL，避免死锁；释放锁通过比对 owner 防止误删他人持有的锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockHelper {

    /** 释放锁 Lua 脚本：比对 owner 后才删除，防止误删他人持有的锁 */
    private static final String UNLOCK_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """;

    /** Hash CAS Lua 脚本：plain mode 匹配 expected 标记后才更新（支持 agentId 等含特殊字符的字段） */
    private static final String HASH_CAS_LUA = """
            local val = redis.call('HGET', KEYS[1], ARGV[1])
            if val == false then return 0 end
            if string.find(val, ARGV[2], 1, true) == nil then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
            return 1
            """;

    private static final RedisScript<Long> UNLOCK_SCRIPT   =
            new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
    private static final RedisScript<Long> HASH_CAS_SCRIPT =
            new DefaultRedisScript<>(HASH_CAS_LUA, Long.class);

    private final StringRedisTemplate redis;

    // ----------------------------------------------------------------
    // 分布式锁
    // ----------------------------------------------------------------

    /**
     * 尝试获取分布式锁（SETNX + EX），获取成功立即返回。
     *
     * @param lockKey 锁 key
     * @param owner   持有者标识（UUID/Thread name 等），用于安全释放
     * @param ttl     锁存活时间（防止死锁）
     * @return true=获取成功
     */
    public boolean tryLock(String lockKey, String owner, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("锁 TTL 必须大于零 lockKey=" + lockKey);
        }
        Boolean ok = redis.opsForValue().setIfAbsent(lockKey, owner, ttl);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 释放分布式锁，仅当 owner 匹配时才删除（Lua CAS 防误删）。
     *
     * @param lockKey 锁 key
     * @param owner   持有者标识，必须与 {@link #tryLock} 时一致
     * @return true=成功释放
     */
    public boolean unlock(String lockKey, String owner) {
        Long result = redis.execute(
                UNLOCK_SCRIPT, Collections.singletonList(lockKey), owner);
        return result != null && result == 1L;
    }

    // ----------------------------------------------------------------
    // Hash 字段 CAS（原子读-校验-写）
    // ----------------------------------------------------------------

    /**
     * 对 Hash 中指定 field 执行 plain-mode CAS：当字段值包含 expectedMarker 时，才写入 newValue。
     *
     * <p>典型用法（业务侧 JSON 序列化后调用）：
     * <pre>
     * String marker = "\"status\":\"WAITING\"";
     * boolean ok = lockHelper.compareAndSetHashField(
     *     "agent:session:queue", sessionId, marker, updatedJson);
     * </pre>
     *
     * @param hashKey         Hash 的 key
     * @param field           Hash 中的 field
     * @param expectedMarker  期望值中应包含的子串（plain mode，不解析 Lua 元字符）
     * @param newValue        CAS 成功后写入的新值
     * @return true=CAS 成功并已写入；false=field 不存在或匹配失败
     */
    public boolean compareAndSetHashField(String hashKey, String field,
                                          String expectedMarker, String newValue) {
        Long result = redis.execute(
                HASH_CAS_SCRIPT,
                Collections.singletonList(hashKey),
                field, expectedMarker, newValue
        );
        return result == 1L;
    }

    // ----------------------------------------------------------------
    // 通用 Lua
    // ----------------------------------------------------------------

    /**
     * 通用 Lua 脚本执行入口（业务侧持有自定义脚本时使用）。
     *
     * @param script 已包装的 RedisScript
     * @param keys   KEYS 数组
     * @param args   ARGV 数组
     * @return 脚本返回值
     */
    public <T> T executeLua(RedisScript<T> script, List<String> keys, Object... args) {
        return redis.execute(script, keys, args);
    }
}
