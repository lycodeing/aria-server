package com.aidevplatform.common.web.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 通用 Redis 缓存操作封装。
 * 统一处理序列化、TTL、key 规范，业务代码只关心存取逻辑。
 *
 * <p>Key 命名规范：{module}:{type}:{id}，例如 emb:query:md5xxx
 * <p>所有缓存必须设置 TTL，禁止永不过期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 获取缓存值并反序列化为指定类型。
     *
     * @param key   缓存 key（格式：{module}:{type}:{id}）
     * @param clazz 目标类型
     * @return 缓存值，不存在或已过期时返回 null
     */
    public <T> T get(String key, Class<T> clazz) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return clazz.cast(value);
    }

    /**
     * 写入缓存，带 TTL（所有缓存必须设置过期时间）。
     *
     * @param key   缓存 key
     * @param value 缓存值（需可序列化）
     * @param ttl   存活时间，不得传 null 或零
     * @throws IllegalArgumentException ttl 为 null 或非正数时抛出
     */
    public void set(String key, Object value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("TTL 必须大于零，key=" + key);
        }
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    /**
     * 删除缓存 key。
     *
     * @param key 缓存 key
     * @return true=删除成功，false=key 不存在
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    /**
     * 判断 key 是否存在。
     *
     * @param key 缓存 key
     * @return true=存在且未过期
     */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 获取缓存；不存在时执行 loader 回源并写入缓存（Cache-Aside + 分布式锁防击穿）。
     *
     * @param key    缓存 key
     * @param clazz  目标类型
     * @param ttl    缓存存活时间
     * @param loader 回源函数，返回 null 则不缓存
     * @return 缓存值或回源值
     */
    public <T> T getOrLoad(String key, Class<T> clazz, Duration ttl,
                            Supplier<T> loader) {
        // 快路径：命中缓存
        T cached = get(key, clazz);
        if (cached != null) {
            return cached;
        }

        // 缓存未命中，用 setIfAbsent 加分布式锁防缓存击穿
        String lockKey = key + ":lock";
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));

        if (Boolean.TRUE.equals(locked)) {
            try {
                T value = loader.get();
                if (value != null) {
                    set(key, value, ttl);
                }
                return value;
            } finally {
                // 确保释放锁，防止死锁
                redisTemplate.delete(lockKey);
            }
        } else {
            // 未获取到锁：等待 50ms 后重读缓存
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            T retried = get(key, clazz);
            // 若仍为空则直接回源（降级处理）
            return retried != null ? retried : loader.get();
        }
    }
}
