package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.repository.VisitorCodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>所有 Redis 操作委托给 {@link VisitorCodeRepository}，本类只含业务规则。
 *
 * <p>安全策略：
 * <ul>
 *   <li>发送频率限制：60s 内同一手机号最多发送 1 次</li>
 *   <li>校验次数限制：连续错误 5 次后锁定 10 分钟</li>
 *   <li>验证码 TTL：5 分钟；访客 Token TTL：2 小时</li>
 * </ul>
 */
@Slf4j
@Service
public class VisitorAuthService {

    private static final Duration ATTEMPTS_WINDOW = Duration.ofMinutes(10);
    private static final Duration TOKEN_TTL       = Duration.ofHours(2);

    /** 密码学安全的随机数生成器，全局共享（线程安全），节约熵源 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VisitorCodeRepository codeRepository;

    @Value("${visitor.auth.code-ttl-minutes:5}")
    private long codeTtlMinutes;

    @Value("${visitor.auth.rate-limit-seconds:60}")
    private long rateLimitSeconds;

    @Value("${visitor.auth.max-attempts:5}")
    private int maxAttempts;

    public VisitorAuthService(VisitorCodeRepository codeRepository) {
        this.codeRepository = codeRepository;
    }

    /**
     * 发送短信验证码。
     *
     * @param phone 11 位手机号
     * @throws BusinessException 429 发送过于频繁
     */
    public void sendCode(String phone) {
        // 频率限制：窗口内只允许发送一次
        if (!codeRepository.tryAcquireRateLimit(phone, rateLimitSeconds)) {
            throw new BusinessException(429, "发送过于频繁，请 " + rateLimitSeconds + " 秒后重试");
        }

        // 生成 6 位数字验证码并写入缓存
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        codeRepository.saveCode(phone, code, codeTtlMinutes);

        // 清除上次的错误次数计数
        codeRepository.resetAttempts(phone);

        // 投递验证码（开发环境日志输出，生产替换为真实短信）
        deliverCode(phone, code);
    }

    /**
     * 校验验证码，成功返回访客 token。
     *
     * @param phone 手机号
     * @param code  用户输入的 6 位验证码
     * @return 访客 token（UUID，TTL 2 小时）
     * @throws BusinessException 400 验证码错误 / 423 已锁定
     */
    public String verifyCode(String phone, String code) {
        // 锁定检查：错误次数超限直接拒绝
        long attempts = codeRepository.getAttempts(phone);
        if (attempts >= maxAttempts) {
            throw new BusinessException(423, "验证码已锁定，请 10 分钟后重新获取");
        }

        String stored = codeRepository.getCode(phone)
                .orElseThrow(() -> new BusinessException(400, "验证码已过期，请重新获取"));

        if (!stored.equals(code)) {
            long newAttempts = codeRepository.incrementAttempts(phone, ATTEMPTS_WINDOW);
            long remaining = maxAttempts - newAttempts;
            throw new BusinessException(400,
                    remaining > 0 ? "验证码错误，还可尝试 " + remaining + " 次" : "验证码错误，账号已锁定");
        }

        // 验证成功：清除验证码 + 错误计数，签发访客 token
        codeRepository.deleteCode(phone);
        codeRepository.resetAttempts(phone);

        String token = UUID.randomUUID().toString().replace("-", "");
        codeRepository.saveToken(token, phone, TOKEN_TTL);

        log.info("[VisitorAuth] 验证成功 phone={}****{}", phone.substring(0, 3), phone.substring(7));
        return token;
    }

    /**
     * 通过 token 解析关联的手机号；token 无效返回 null。
     */
    public String resolvePhone(String token) {
        if (token == null || token.isBlank()) return null;
        return codeRepository.resolveToken(token).orElse(null);
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

    /** 获取访客 token 的完整信息（供其他服务调用） */
    public Map<String, String> getTokenInfo(String token) {
        String phone = resolvePhone(token);
        if (phone == null) return Map.of();
        return Map.of("token", token, "phone", phone);
    }
}
