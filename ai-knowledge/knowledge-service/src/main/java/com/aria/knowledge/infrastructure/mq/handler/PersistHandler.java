package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 责任链 Step 7：幂等写入向量库。
 * 先删除该文档的旧 chunk，再批量插入新 chunk，保证 MQ 重试场景下数据一致性。
 *
 * <p>并发保护：使用 Redisson RLock（watchdog 模式）防止同一文档多个消费者并发 delete+insert 互相覆盖。
 * watchdog 模式（不传 leaseTime）下 Redisson 每 10s 自动续期，彻底消除大文档写入超过 TTL 导致锁提前
 * 释放、另一消费者并发写入造成数据损坏的风险（旧实现 SETNX TTL=5min 存在此问题）。
 *
 * <p>锁竞争失败时抛出异常，Spring AMQP 自动 nack 并重试，让持有锁的消费者完成后再处理。
 */
@Slf4j
@Order(8)
@Component
@RequiredArgsConstructor
public class PersistHandler implements IngestHandler {

    private static final String LOCK_PREFIX   = "lock:ingest:persist:";
    private static final int    LOCK_WAIT_SEC = 10;   // 最多等待 10s 获取锁

    private final KnowledgeChunkRepository chunkRepository;
    private final RedissonClient           redissonClient;

    @Override
    public void handle(IngestContext ctx) {
        String docId   = ctx.getEvent().getDocId();
        String lockKey = LOCK_PREFIX + docId;

        // watchdog 模式：不传 leaseTime，Redisson 每 lockWatchdogTimeout/3（默认 10s）自动续期，
        // 持有锁直到主动 unlock()，彻底消除 TTL 过期竞态
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文档持久化锁等待被中断: " + docId, e);
        }

        if (!acquired) {
            log.warn("[持久化] 获取分布式锁失败，等待重试 docId={}", docId);
            throw new IllegalStateException("文档持久化锁竞争失败，等待重试: " + docId);
        }

        try {
            // 幂等：先删旧 chunk，再写新 chunk，避免 MQ 重试导致重复数据
            chunkRepository.deleteByDocId(docId);
            chunkRepository.saveAll(ctx.getChunks());
            log.info("[持久化] docId={} 写入 chunk 数={}", docId, ctx.getChunks().size());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
