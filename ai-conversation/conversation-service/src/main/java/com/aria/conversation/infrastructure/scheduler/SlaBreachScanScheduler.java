package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SLA 违规分片扫描调度器。
 *
 * <p>分片策略：{@code MOD(ABS(HASHTEXT(session_id)), shardCount)}。
 * 每个分片独立持有一把 Redisson 分布式锁，保证多 Pod 部署时同一分片只由一个实例处理。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code now} 在每轮扫描入口统一捕获一次，同批次所有会话使用相同基准时间，
 *       避免跨分片时间漂移引发评估不一致</li>
 *   <li>使用 {@code tryLock(0, ...)} 非阻塞抢锁：抢不到直接跳过该分片，
 *       不阻塞其他分片的处理</li>
 *   <li>单会话异常不中断整批扫描，每条会话异常独立 catch 并记录</li>
 *   <li>lockLeaseTime 设为 25s（小于 scanIntervalMs 30s），
 *       防止锁未释放时下一轮已开始</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachScanScheduler {

    private static final String LOCK_KEY_PREFIX = "lock:scheduler:sla-scan:shard:";
    private static final long   LEASE_SECONDS   = 25L;

    private final RedissonClient     redissonClient;
    private final ConversationMapper conversationMapper;
    private final SlaBreachDetector  slaBreachDetector;
    private final SlaProperties      slaProperties;

    /**
     * SLA 分片扫描入口。
     *
     * <p>fixedDelay 语义：上次执行完成后才开始下一次计时，避免执行时间超过间隔导致任务堆积。
     * 间隔和初始延迟从 {@link SlaProperties} 读取，支持运行时配置覆盖。
     */
    @Scheduled(fixedDelayString   = "${sla.scan-interval-ms:30000}",
               initialDelayString = "${sla.initial-delay-ms:10000}")
    public void scan() {
        int shardCount = slaProperties.getShardCount();
        // now 在分片遍历前统一捕获，保证同批次所有会话的检测基准时间一致
        OffsetDateTime now = OffsetDateTime.now();

        for (int i = 0; i < shardCount; i++) {
            final int shardIndex = i;
            RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + shardIndex);
            boolean acquired;
            try {
                // waitTime=0：非阻塞抢锁，抢不到直接跳过，不影响其他分片
                acquired = lock.tryLock(0, LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[SLA-scan] interrupted while acquiring lock, aborting scan cycle");
                return;
            }
            if (!acquired) {
                log.debug("[SLA-scan] shard={} lock held by another instance, skipping", shardIndex);
                continue;
            }

            try {
                List<ConversationEntity> sessions =
                        conversationMapper.selectActiveByShardIndex(shardIndex, shardCount);
                log.debug("[SLA-scan] shard={}/{} sessions={} now={}",
                        shardIndex, shardCount, sessions.size(), now);

                sessions.forEach(session -> {
                    try {
                        slaBreachDetector.check(session, now);
                    } catch (Exception e) {
                        log.error("[SLA-scan] check failed session={}",
                                session.getSessionId(), e);
                    }
                });
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
