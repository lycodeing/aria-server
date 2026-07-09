package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.mq.DocIngestEvent;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersistHandler 分布式锁行为")
class PersistHandlerLockTest {

    @Mock KnowledgeChunkRepository chunkRepository;
    @Mock RedissonClient redissonClient;
    @Mock RLock rLock;

    private IngestContext buildCtx(String docId) {
        DocIngestEvent event = mock(DocIngestEvent.class);
        when(event.getDocId()).thenReturn(docId);
        IngestContext ctx = mock(IngestContext.class);
        when(ctx.getEvent()).thenReturn(event);
        // lenient: chunks not accessed when lock is not acquired
        lenient().when(ctx.getChunks()).thenReturn(List.of());
        return ctx;
    }

    @Test
    @DisplayName("锁获取失败时抛 IllegalStateException，触发 MQ nack 重试")
    void lock_not_acquired_throws_for_nack() throws InterruptedException {
        when(redissonClient.getLock(startsWith("lock:ingest:persist:"))).thenReturn(rLock);
        // watchdog 模式：只传 waitTime，不传 leaseTime
        when(rLock.tryLock(10, TimeUnit.SECONDS)).thenReturn(false);

        PersistHandler handler = new PersistHandler(chunkRepository, redissonClient);
        assertThatThrownBy(() -> handler.handle(buildCtx("doc-001")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("doc-001");

        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("锁获取成功时执行 delete+insert，完成后释放锁")
    void lock_acquired_persists_and_unlocks() throws InterruptedException {
        when(redissonClient.getLock(startsWith("lock:ingest:persist:"))).thenReturn(rLock);
        when(rLock.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        PersistHandler handler = new PersistHandler(chunkRepository, redissonClient);
        handler.handle(buildCtx("doc-002"));

        verify(chunkRepository).deleteByDocId("doc-002");
        verify(chunkRepository).saveAll(any());
        verify(rLock).unlock();
    }
}
