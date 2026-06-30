package com.aidevplatform.common.web.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Redis 缓存操作封装（职责：缓存）。
 *
 * <p>本类只负责"缓存"语义：结构化键值存取（String / Hash / List / Pipeline），强制 TTL。
 * 分布式锁请使用 {@link RedisLockHelper}，计数器/限流请使用 {@link RedisCounterHelper}。
 *
 * <p>Key 命名规范：{@code {module}:{type}:{id}}（如 {@code session:queue:123}），
 * 所有缓存必须设置 TTL（{@link #set(String, Object, Duration)} 强制校验），
 * 避免出现"永不过期"的脏数据。
 *
 * <p>内部使用 {@link StringRedisTemplate} 操作，复杂对象由 {@link ObjectMapper} 序列化为 JSON 字符串。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private final StringRedisTemplate redis;
    private final ObjectMapper        objectMapper;

    // ----------------------------------------------------------------
    // String 类型操作
    // ----------------------------------------------------------------

    /** 获取 String 类型缓存，不存在返回 null */
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    /** 获取并反序列化为指定类型对象 */
    public <T> T get(String key, Class<T> clazz) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        try {
            return clazz == String.class ? clazz.cast(raw) : objectMapper.readValue(raw, clazz);
        } catch (JsonProcessingException e) {
            log.warn("[RedisCache] 反序列化失败 key={} class={}", key, clazz.getName(), e);
            return null;
        }
    }

    /**
     * 写入缓存（强制 TTL）。
     *
     * @param key   缓存 key
     * @param value 缓存值（String 直接写入，其他类型 JSON 序列化）
     * @param ttl   存活时间，不得为 null 或非正数
     */
    public void set(String key, Object value, Duration ttl) {
        validateTtl(key, ttl);
        String raw;
        if (value instanceof String s) {
            raw = s;
        } else {
            try {
                raw = objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("缓存值无法 JSON 序列化 key=" + key, e);
            }
        }
        redis.opsForValue().set(key, raw, ttl);
    }

    /** 删除 key */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redis.delete(key));
    }

    /** 判断 key 是否存在 */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /** 设置/刷新 key 的过期时间 */
    public boolean expire(String key, Duration ttl) {
        validateTtl(key, ttl);
        return Boolean.TRUE.equals(redis.expire(key, ttl));
    }

    // ----------------------------------------------------------------
    // Hash 类型操作
    // ----------------------------------------------------------------

    /** Hash GET，field 不存在返回 null */
    public String hGet(String key, String field) {
        Object raw = redis.opsForHash().get(key, field);
        return raw != null ? raw.toString() : null;
    }

    /** Hash PUT，业务侧自行序列化为 String */
    public void hPut(String key, String field, String value) {
        redis.opsForHash().put(key, field, value);
    }

    /** Hash DELETE 一个或多个 field */
    public Long hDelete(String key, Object... fields) {
        return redis.opsForHash().delete(key, fields);
    }

    /** 获取整张 Hash 的所有 entries */
    public Map<Object, Object> hEntries(String key) {
        return redis.opsForHash().entries(key);
    }

    /** 判断 Hash 中是否存在指定 field */
    public boolean hHasKey(String key, String field) {
        return Boolean.TRUE.equals(redis.opsForHash().hasKey(key, field));
    }

    /** 获取 Hash 多个 field 的值（按入参顺序，缺失为 null） */
    public Map<String, String> hMultiGet(String key, List<String> fields) {
        List<Object> values = redis.opsForHash().multiGet(key, new java.util.ArrayList<>(fields));
        Map<String, String> result = new HashMap<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            Object v = values.get(i);
            result.put(fields.get(i), v != null ? v.toString() : null);
        }
        return result;
    }

    // ----------------------------------------------------------------
    // List 类型操作
    // ----------------------------------------------------------------

    /** List 范围查询，end=-1 表示到末尾 */
    public List<String> lRange(String key, long start, long end) {
        return redis.opsForList().range(key, start, end);
    }

    /** List 右推入一个元素，返回 push 后的长度 */
    public Long lRightPush(String key, String value) {
        return redis.opsForList().rightPush(key, value);
    }

    /** List 截断保留指定范围（含 start、end） */
    public void lTrim(String key, long start, long end) {
        redis.opsForList().trim(key, start, end);
    }

    // ----------------------------------------------------------------
    // Pipeline / RedisCallback
    // ----------------------------------------------------------------

    /**
     * 通过 Pipeline 批量执行 Redis 命令，减少 RTT。
     *
     * <p>注意：Pipeline 不是事务，多个命令独立执行，业务侧若需原子性请使用 Lua 脚本
     * （见 {@link RedisLockHelper#executeLua}）。
     *
     * @param callback Redis 命令回调（直接操作底层 connection）
     * @return 每条命令的返回值列表
     */
    public List<Object> executePipeline(RedisCallback<Object> callback) {
        return redis.executePipelined(callback);
    }

    // ----------------------------------------------------------------
    // 高阶语义
    // ----------------------------------------------------------------

    /**
     * Cache-Aside：缓存未命中时回源并写入。
     *
     * <p>简单实现（不防击穿），需要防击穿请显式用 {@link RedisLockHelper#tryLock} 包裹回源逻辑。
     *
     * @param key    缓存 key
     * @param clazz  目标类型
     * @param ttl    存活时间
     * @param loader 回源函数，返回 null 不写入缓存
     */
    public <T> T getOrLoad(String key, Class<T> clazz, Duration ttl, Supplier<T> loader) {
        T cached = get(key, clazz);
        if (cached != null) {
            return cached;
        }
        T value = loader.get();
        if (value != null) {
            set(key, value, ttl);
        }
        return value;
    }

    // ----------------------------------------------------------------
    // 内部工具
    // ----------------------------------------------------------------

    private void validateTtl(String key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("TTL 必须大于零 key=" + key);
        }
    }
}
