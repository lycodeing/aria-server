package com.aria.common.web.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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

    /**
     * 获取并反序列化为指定类型对象。
     *
     * @param key   缓存 key
     * @param clazz 目标类型
     * @return 反序列化后的对象；key 不存在返回 null
     * @throws CacheDeserializeException 缓存值无法反序列化为目标类型（数据腐化），
     *         由调用方决定是删除重建还是降级，避免被静默当作"未命中"重复回源
     */
    public <T> T get(String key, Class<T> clazz) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        try {
            return clazz == String.class ? clazz.cast(raw) : objectMapper.readValue(raw, clazz);
        } catch (JsonProcessingException e) {
            log.error("[RedisCache] 反序列化失败 key={} class={}", key, clazz.getName(), e);
            throw new CacheDeserializeException(key,
                    "缓存值无法反序列化为 " + clazz.getName() + " key=" + key, e);
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

    /**
     * Hash PUT（不主动设置 TTL），业务侧自行序列化为 String。
     *
     * <p>注意：Redis Hash 的 TTL 是 key 级别而非 field 级别。
     * 若 Hash 中 field 可能长期累积（如会话队列、在线注册表），调用方应：
     * <ol>
     *   <li>使用 {@link #hPut(String, String, String, Duration)} 重载，每次写入刷新 TTL</li>
     *   <li>或在适当时机手动调用 {@link #expire} 续期</li>
     *   <li>或在 field 不再使用时主动调用 {@link #hDelete} 清理</li>
     * </ol>
     */
    public void hPut(String key, String field, String value) {
        redis.opsForHash().put(key, field, value);
    }

    /**
     * Hash PUT 并刷新 key 级 TTL（避免 Hash 永不过期）。
     *
     * <p>典型用法：会话队列等动态 Hash，每次写入 field 同时延长整体过期时间。
     *
     * @param key   Hash key
     * @param field 字段名
     * @param value 字段值
     * @param ttl   key 的过期时间（每次写入都会刷新）
     */
    public void hPut(String key, String field, String value, Duration ttl) {
        validateTtl(key, ttl);
        redis.opsForHash().put(key, field, value);
        redis.expire(key, ttl);
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
        List<Object> values = redis.opsForHash().multiGet(key, new ArrayList<>(fields));
        // 按负载因子修正初始容量，避免触发一次扩容
        Map<String, String> result = new HashMap<>((int) (fields.size() / 0.75f) + 1);
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

    /**
     * List 一次性右推入多个元素（原子操作）。
     *
     * <p>等价于 {@code RPUSH key v1 v2 v3 ...}，Redis 服务端单条命令完成，
     * 杜绝并发场景下多个客户端 push 命令交错导致的元素错位。
     *
     * @param key    List key
     * @param values 待推入的元素序列（不允许为 null）
     * @return push 后的列表长度
     */
    public Long lRightPushAll(String key, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                throw new IllegalArgumentException("values 不允许包含 null，index=" + i + " key=" + key);
            }
        }
        return redis.opsForList().rightPushAll(key, values);
    }

    /** List 截断保留指定范围（含 start、end） */
    public void lTrim(String key, long start, long end) {
        redis.opsForList().trim(key, start, end);
    }

    // ----------------------------------------------------------------
    // List 高阶语义（封装常见 Pipeline 模式）
    // ----------------------------------------------------------------

    /**
     * 全量替换 List 内容（DEL + RPUSH 序列 + EXPIRE 通过 Pipeline 批量执行）。
     *
     * <p>典型用法：AI 流式回复完成后用最新历史覆盖会话记录，
     * 通过 Pipeline 将多条命令合并到单次 RTT，并发场景下也优于"先 delete 再循环 rPush"。
     *
     * <p>注意：Pipeline 不是事务，命令在 Redis 服务端独立执行；
     * 若另一客户端在 Pipeline 命令间插入操作，仍可能读到中间状态，
     * 但本方法的批量发送已显著降低竞态窗口。
     *
     * @param key      List key
     * @param elements 替换后的完整元素序列（顺序写入）
     * @param ttl      列表 TTL
     */
    public void replaceListAtomically(String key, List<String> elements, Duration ttl) {
        validateTtl(key, ttl);
        // 工具层不兜底 null 元素：写入空串会污染读端配对解析（如 [role,content] 序列），
        // 调用方必须保证传入完整数据
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) == null) {
                throw new IllegalArgumentException("elements 不允许包含 null，index=" + i + " key=" + key);
            }
        }
        byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
        long   ttlSec = ttl.getSeconds();
        redis.executePipelined((RedisCallback<Object>) connection -> {
            connection.keyCommands().del(rawKey);
            for (String element : elements) {
                connection.listCommands().rPush(rawKey, element.getBytes(StandardCharsets.UTF_8));
            }
            connection.keyCommands().expire(rawKey, ttlSec);
            return null;
        });
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
