package com.aidevplatform.conversation.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 访客手机号身份验证服务。
 *
 * <p>验证码通过 Redis 存储，开发环境通过日志输出验证码；
 * 生产环境替换 {@link #deliverCode} 方法接入真实短信服务商（阿里云/腾讯云）。
 *
 * <p>安全策略：
 * <ul>
 *   <li>发送频率限制：60s 内同一手机号最多发送 1 次（{@code visitor:sms:rate:{phone}}）</li>
 *   <li>校验次数限制：连续错误 5 次后锁定 10 分钟（{@code visitor:sms:attempts:{phone}}）</li>
 *   <li>验证码 TTL：5 分钟（{@code visitor:sms:{phone}}）</li>
 *   <li>访客 Token TTL：2 小时（{@code visitor:token:{token}}，value=phone）</li>
 * </ul>
 */
@Slf4j
@Service
public class VisitorAuthService {

    private static final String KEY_CODE     = "visitor:sms:";
    private static final String KEY_RATE     = "visitor:sms:rate:";
    private static final String KEY_ATTEMPTS = "visitor:sms:attempts:";
    private static final String KEY_TOKEN    = "visitor:token:";

    private final StringRedisTemplate redis;
    /** 密码学安全的随机数生成器，防止验证码被预测 */
    private final SecureRandom        random = new SecureRandom();

    @Value("${visitor.auth.code-ttl-minutes:5}")
    private long codeTtlMinutes;

    @Value("${visitor.auth.rate-limit-seconds:60}")
    private long rateLimitSeconds;

    @Value("${visitor.auth.max-attempts:5}")
    private int maxAttempts;

    public VisitorAuthService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 发送短信验证码。
     *
     * @param phone 11 位手机号
     * @throws com.aidevplatform.common.core.exception.BusinessException 429 发送过于频繁
     */
    public void sendCode(String phone) {
        // 频率限制：60s 内不允许重发
        String rateKey = KEY_RATE + phone;
        Boolean first = redis.opsForValue().setIfAbsent(rateKey, "1", Duration.ofSeconds(rateLimitSeconds));
        if (Boolean.FALSE.equals(first)) {
            throw new com.aidevplatform.common.core.exception.BusinessException(
                    429, "发送过于频繁，请 " + rateLimitSeconds + " 秒后重试");
        }

        // 生成 6 位数字验证码并存入 Redis
        String code = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(KEY_CODE + phone, code, Duration.ofMinutes(codeTtlMinutes));

        // 清除旧的错误次数计数
        redis.delete(KEY_ATTEMPTS + phone);

        // 投递验证码（开发环境日志输出，生产环境替换为真实短信）
        deliverCode(phone, code);
    }

    /**
     * 校验验证码，成功返回访客 token。
     *
     * @param phone 手机号
     * @param code  用户输入的 6 位验证码
     * @return 访客 token（UUID，TTL 2 小时）
     * @throws com.aidevplatform.common.core.exception.BusinessException 400 验证码错误 | 423 已锁定
     */
    public String verifyCode(String phone, String code) {
        String attemptsKey = KEY_ATTEMPTS + phone;

        // 检查是否已锁定（错误次数超限）
        String attemptsStr = redis.opsForValue().get(attemptsKey);
        int attempts = attemptsStr != null ? Integer.parseInt(attemptsStr) : 0;
        if (attempts >= maxAttempts) {
            throw new com.aidevplatform.common.core.exception.BusinessException(
                    423, "验证码已锁定，请 10 分钟后重新获取");
        }

        // 取出 Redis 中存储的验证码
        String stored = redis.opsForValue().get(KEY_CODE + phone);
        if (stored == null) {
            throw new com.aidevplatform.common.core.exception.BusinessException(
                    400, "验证码已过期，请重新获取");
        }

        if (!stored.equals(code)) {
            // 错误次数 +1，首次写入 TTL=10min（与锁定时长一致）
            redis.opsForValue().increment(attemptsKey);
            redis.expire(attemptsKey, Duration.ofMinutes(10));
            int remaining = maxAttempts - attempts - 1;
            throw new com.aidevplatform.common.core.exception.BusinessException(
                    400, remaining > 0 ? "验证码错误，还可尝试 " + remaining + " 次" : "验证码错误，账号已锁定");
        }

        // 验证成功：清除验证码和错误计数，生成访客 token
        redis.delete(KEY_CODE + phone);
        redis.delete(KEY_ATTEMPTS + phone);

        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(KEY_TOKEN + token, phone, Duration.ofHours(2));

        log.info("[VisitorAuth] 验证成功 phone={}****{}", phone.substring(0, 3), phone.substring(7));
        return token;
    }

    /**
     * 验证访客 token，返回关联的手机号；token 无效返回 null。
     *
     * @param token 访客 token
     * @return 手机号，或 null（token 无效/已过期）
     */
    public String resolvePhone(String token) {
        if (token == null || token.isBlank()) return null;
        return redis.opsForValue().get(KEY_TOKEN + token);
    }

    /**
     * 投递验证码（可替换为真实短信服务）。
     *
     * <p>开发环境：INFO 日志输出；生产环境注入短信服务 Bean 替换此方法。
     * TODO(SMS-PROD): 接入阿里云/腾讯云短信服务替换此实现
     */
    protected void deliverCode(String phone, String code) {
        log.info("[VisitorAuth][DEV] 验证码发送 phone={}**** code={}", phone.substring(0, 3), code);
    }

    /**
     * 获取访客 token 的完整信息（供其他服务调用）。
     */
    public Map<String, String> getTokenInfo(String token) {
        String phone = resolvePhone(token);
        if (phone == null) return Map.of();
        return Map.of("token", token, "phone", phone);
    }
}
