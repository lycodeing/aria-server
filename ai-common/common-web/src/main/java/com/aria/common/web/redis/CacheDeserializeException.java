package com.aria.common.web.redis;

/**
 * Redis 缓存反序列化失败异常。
 *
 * <p>当 {@link RedisCacheHelper#get(String, Class)} 取到的值无法反序列化为目标类型时抛出，
 * 让调用方主动决策：删除脏数据后重建，或降级使用默认值。
 *
 * <p>区别于"key 不存在"（返回 null），数据腐化是确切错误，必须显式处理。
 */
public class CacheDeserializeException extends RuntimeException {

    private final String key;

    public CacheDeserializeException(String key, String message, Throwable cause) {
        super(message, cause);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
