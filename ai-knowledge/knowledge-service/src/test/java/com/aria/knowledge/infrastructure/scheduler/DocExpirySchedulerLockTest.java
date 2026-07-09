package com.aria.knowledge.infrastructure.scheduler;

import com.aria.knowledge.application.service.DocExpiryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocExpiryScheduler 分布式锁行为")
class DocExpirySchedulerLockTest {

    @Mock DocExpiryService docExpiryService;
    @Mock RedissonClient redissonClient;
    @Mock RLock rLock;

    @Test
    @DisplayName("锁获取失败时跳过执行，不调用 docExpiryService")
    void lock_not_acquired_skips_execution() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:doc-expiry")).thenReturn(rLock);
        when(rLock.tryLock(0, 10, TimeUnit.MINUTES)).thenReturn(false);

        DocExpiryScheduler scheduler = new DocExpiryScheduler(docExpiryService, redissonClient);
        scheduler.deprecateExpiredDocs();

        verifyNoInteractions(docExpiryService);
    }

    @Test
    @DisplayName("锁获取成功时执行清理，完成后释放锁")
    void lock_acquired_executes_and_unlocks() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:doc-expiry")).thenReturn(rLock);
        when(rLock.tryLock(0, 10, TimeUnit.MINUTES)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        DocExpiryScheduler scheduler = new DocExpiryScheduler(docExpiryService, redissonClient);
        scheduler.deprecateExpiredDocs();

        verify(docExpiryService).deprecateExpired(any());
        verify(rLock).unlock();
    }
}
