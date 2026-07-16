package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.application.service.CsatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * CSAT 评价邀请过期定时任务。
 *
 * <p>每 10 分钟扫描 {@code status=PENDING} 且 {@code expired_at < NOW()} 的记录，
 * 批量更新为 {@code EXPIRED}。
 *
 * <p>多实例保护：使用 Redisson RLock（leaseTime=10min），同一时刻只有一个 Pod 执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CsatExpiryScheduler {

    private static final String LOCK_KEY = "lock:scheduler:csat-expiry";

    private final CsatService      csatService;
    private final RedissonClient   redissonClient;

    /**
     * fixedDelay 保证上次执行完成后才开始下一次计时，避免执行时间超过间隔导致堆积。
     * 10 分钟 = 600_000ms。
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void expireOverdueCsatRatings() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 10, TimeUnit.MINUTES);
            if (!acquired) {
                log.debug("[CsatExpiry] 其他实例正在执行，跳过本次触发");
                return;
            }
            log.info("[CsatExpiry] 开始执行 CSAT 过期清理");
            int count = csatService.expirePending();
            log.info("[CsatExpiry] 完成，过期 {} 条", count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[CsatExpiry] 锁等待被中断，跳过本次执行");
        } catch (Exception e) {
            log.error("[CsatExpiry] 执行异常", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
