package com.aria.knowledge.infrastructure.scheduler;

import com.aria.knowledge.application.service.DocExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 文档过期清理定时任务。
 * 每天凌晨 02:00 执行，业务逻辑全部委托 {@link DocExpiryService}。
 *
 * <p>多实例保护：使用 Redisson RLock（固定 leaseTime=10min）保证同一时刻只有一个 Pod 执行。
 * 与 watchdog 模式不同，定时任务有明确的预期执行时间上界，使用固定 leaseTime 更合适。
 *
 * <p>可通过配置 {@code knowledge.expiry.scheduler.enabled=false} 在开发/测试环境关闭此任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name        = "knowledge.expiry.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class DocExpiryScheduler {

    private static final String LOCK_KEY = "lock:scheduler:doc-expiry";

    private final DocExpiryService docExpiryService;
    private final RedissonClient   redissonClient;

    /**
     * 每天凌晨 02:00 执行过期文档下线清理。
     * 锁获取失败说明其他实例正在执行，直接跳过。
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void deprecateExpiredDocs() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired;
        try {
            // waitTime=0：不等待，立即返回；leaseTime=10min：固定持有时长，非 watchdog 模式
            acquired = lock.tryLock(0, 10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[DocExpiryScheduler] 锁等待被中断，跳过本次执行");
            return;
        }
        if (!acquired) {
            log.debug("[DocExpiryScheduler] 其他实例正在执行过期清理，跳过本次触发");
            return;
        }
        try {
            log.info("[DocExpiryScheduler] 开始执行文档过期清理 date={}", LocalDate.now());
            docExpiryService.deprecateExpired(LocalDate.now());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
