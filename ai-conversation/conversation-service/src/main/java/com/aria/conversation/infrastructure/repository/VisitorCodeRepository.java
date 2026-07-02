package com.aria.conversation.infrastructure.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.common.web.redis.RedisCounterHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 访客验证码 Redis 仓储。
 *
 * <p>封装访客短信验证码、发送频率限制、错误次数计数、访客 Token 的所有 Redis 操作，
 * 对上层（{@link com.aria.conversation.application.service.VisitorAuthService}）
 * 屏蔽 key 命名、TTL 策略等基础设施细节。
 *
 * <p>Redis 数据结构：
 * <pre>
 *   visitor:sms:{phone}          String  验证码（6位数字，TTL 可配）
 *   visitor:sms:rate:{phone}     String  发送频率锁（SET NX，60s 窗口）
 *   visitor:sms:attempts:{phone} String  错误次数计数（Lua INCR，10min 窗口）
 *   visitor:token:{token}        String  访客 token → phone 映射（2h TTL）
 * </pre>
 */
@Repository
@RequiredArgsConstructor
public class VisitorCodeRepository {

    private static final String KEY_CODE     = "visitor:sms:";
    private static final String KEY_RATE     = "visitor:sms:rate:";
    private static final String KEY_ATTEMPTS = "visitor:sms:attempts:";
    private static final String KEY_TOKEN    = "visitor:token:";

    private final RedisCacheHelper   cache;
    private final RedisCounterHelper counter;

    // ----------------------------------------------------------------
    // 验证码
    // ----------------------------------------------------------------

    /** 保存验证码，TTL 由调用方传入（单位：分钟）。 */
    public void saveCode(String phone, String code, long ttlMinutes) {
        cache.set(KEY_CODE + phone, code, Duration.ofMinutes(ttlMinutes));
    }

    /** 获取当前有效验证码，不存在或已过期返回 empty。 */
    public Optional<String> getCode(String phone) {
        return Optional.ofNullable(cache.get(KEY_CODE + phone));
    }

    /** 删除验证码（验证成功或主动清除时调用）。 */
    public void deleteCode(String phone) {
        cache.delete(KEY_CODE + phone);
    }

    // ----------------------------------------------------------------
    // 发送频率限制
    // ----------------------------------------------------------------

    /**
     * 尝试占用发送频率窗口。
     * SET NX 原子操作：窗口内首次调用返回 true，重复调用返回 false。
     *
     * @param phone             手机号
     * @param rateLimitSeconds  限流窗口长度（秒）
     * @return true 表示可发送，false 表示窗口内已发过
     */
    public boolean tryAcquireRateLimit(String phone, long rateLimitSeconds) {
        return counter.firstAccess(KEY_RATE + phone, Duration.ofSeconds(rateLimitSeconds));
    }

    // ----------------------------------------------------------------
    // 错误次数
    // ----------------------------------------------------------------

    /** 获取当前错误次数（窗口未到期时返回累计值，否则返回 0）。 */
    public long getAttempts(String phone) {
        return counter.get(KEY_ATTEMPTS + phone);
    }

    /**
     * 错误次数 +1。
     *
     * @param phone          手机号
     * @param windowDuration 计数窗口（错误次数超限后锁定的时长）
     * @return 自增后的错误次数
     */
    public long incrementAttempts(String phone, Duration windowDuration) {
        return counter.increment(KEY_ATTEMPTS + phone, windowDuration);
    }

    /** 清除错误次数（发送新验证码或验证成功时调用）。 */
    public void resetAttempts(String phone) {
        counter.reset(KEY_ATTEMPTS + phone);
    }

    // ----------------------------------------------------------------
    // 访客 Token
    // ----------------------------------------------------------------

    /**
     * 签发访客 token，写入 phone → token 映射。
     *
     * @param token    访客 token（UUID）
     * @param phone    关联手机号
     * @param tokenTtl token 有效期
     */
    public void saveToken(String token, String phone, Duration tokenTtl) {
        cache.set(KEY_TOKEN + token, phone, tokenTtl);
    }

    /**
     * 通过 token 解析关联的手机号。
     *
     * @param token 访客 token
     * @return 手机号，token 无效或已过期时返回 empty
     */
    public Optional<String> resolveToken(String token) {
        return Optional.ofNullable(cache.get(KEY_TOKEN + token));
    }
}
