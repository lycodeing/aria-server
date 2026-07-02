package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.common.web.redis.RedisLockHelper;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 责任链 Step 7：幂等写入向量库。
 * 先删除该文档的旧 chunk，再批量插入新 chunk，保证 MQ 重试场景下数据一致性。
 *
 * <p>并发保护：使用分布式锁（key: {@code lock:ingest:persist:{docId}}）防止同一文档
 * 多个消费者并发 delete+insert 互相覆盖（reingest 或 MQ 至少一次投递引发的重复消费场景）。
 * 锁竞争失败时抛出异常，Spring AMQP 自动 nack 并重试，让持有锁的消费者完成后再处理。
 */
@Slf4j
@Order(8)
@Component
@RequiredArgsConstructor
public class PersistHandler implements IngestHandler {

    /** 分布式锁 TTL：覆盖向量化 + 写库的最大耗时（5 分钟） */
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final String   LOCK_PREFIX = "lock:ingest:persist:";

    private final KnowledgeChunkRepository chunkRepository;
    private final RedisLockHelper          lockHelper;

    @Override
    public void handle(IngestContext ctx) {
        String docId   = ctx.getEvent().getDocId();
        String lockKey = LOCK_PREFIX + docId;
        // owner 使用 docId + 线程 ID，保证同进程内不同线程不会互相解锁
        String owner   = docId + ":" + Thread.currentThread().getId();

        boolean locked = lockHelper.tryLock(lockKey, owner, LOCK_TTL);
        if (!locked) {
            // 另一消费者正在处理同一文档，抛出异常触发 nack+重试，等待锁释放
            log.warn("[持久化] 获取分布式锁失败，等待重试 docId={}", docId);
            throw new IllegalStateException("文档持久化锁竞争失败，等待重试: " + docId);
        }
        try {
            // 幂等：先删旧 chunk，再写新 chunk，避免 MQ 重试导致重复数据
            chunkRepository.deleteByDocId(docId);
            chunkRepository.saveAll(ctx.getChunks());
            log.info("[持久化] docId={}，写入 chunk 数={}", docId, ctx.getChunks().size());
        } finally {
            lockHelper.unlock(lockKey, owner);
        }
    }
}
