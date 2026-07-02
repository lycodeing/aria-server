package com.aria.knowledge.infrastructure.scheduler;

import com.aria.common.web.redis.RedisLockHelper;
import com.aria.knowledge.application.service.DocExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 文档过期清理定时任务。
 * 每天凌晨 02:00 执行，业务逻辑全部委托 {@link DocExpiryService}。
 *
 * <p>多实例保护：使用分布式锁（{@link RedisLockHelper}）保证同一时刻只有一个 Pod 执行，
 * 防止多实例并发触发时产生重复清理或事务交叉。
 *
 * <p>可通过配置 {@code knowledge.expiry.scheduler.enabled=false} 在开发/测试环境关闭此任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name        = "knowledge.expiry.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true   // 默认开启，明确设置 false 才关闭
)
public class DocExpiryScheduler {

    /** 分布式锁 key，全局唯一 */
    private static final String LOCK_KEY  = "lock:scheduler:doc-expiry";
    /** 锁 TTL：覆盖清理任务最大预期耗时（10 分钟），防止死锁 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final DocExpiryService docExpiryService;
    private final RedisLockHelper  lockHelper;

    /**
     * 每天凌晨 02:00 执行过期文档下线清理。
     * 获取分布式锁失败说明其他实例正在执行，直接跳过。
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void deprecateExpiredDocs() {
        String owner = UUID.randomUUID().toString();
        if (!lockHelper.tryLock(LOCK_KEY, owner, LOCK_TTL)) {
            log.info("[DocExpiryScheduler] 其他实例正在执行过期清理，跳过本次触发");
            return;
        }
        try {
            log.info("[DocExpiryScheduler] 开始执行文档过期清理，date={}", LocalDate.now());
            docExpiryService.deprecateExpired(LocalDate.now());
        } finally {
            lockHelper.unlock(LOCK_KEY, owner);
        }
    }
}
