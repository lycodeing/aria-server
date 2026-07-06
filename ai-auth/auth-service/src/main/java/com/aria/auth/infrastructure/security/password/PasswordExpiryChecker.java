package com.aria.auth.infrastructure.security.password;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 密码过期检查器。
 *
 * <p>通过配置项 {@code adp.auth.password.expire-days}（默认 90 天）判断密码是否过期。
 * {@code null} 的 {@code passwordChangedAt} 视为从未修改过，即视为已过期。
 */
@Slf4j
@Component
public class PasswordExpiryChecker {

    /**
     * 密码过期天数，默认 90 天
     */
    @Value("${adp.auth.password.expire-days:90}")
    private long expireDays;

    /**
     * 判断密码是否已过期。
     *
     * @param passwordChangedAt 最后一次修改密码的时间，为 {@code null} 时视为从未修改（已过期）
     * @return 已过期返回 {@code true}，否则返回 {@code false}
     */
    public boolean isExpired(Instant passwordChangedAt) {
        if (passwordChangedAt == null) {
            log.warn("[PasswordExpiry] passwordChangedAt 为 null，视为从未修改过（已过期）");
            return true;
        }
        return daysUntilExpiry(passwordChangedAt) <= 0;
    }

    /**
     * 计算距密码过期的剩余天数。
     *
     * @param passwordChangedAt 最后一次修改密码的时间，为 {@code null} 时返回 0（立即过期）
     * @return 剩余天数，≤ 0 表示已过期
     */
    public long daysUntilExpiry(Instant passwordChangedAt) {
        if (passwordChangedAt == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(Instant.now(), passwordChangedAt.plus(expireDays, ChronoUnit.DAYS));
    }
}
