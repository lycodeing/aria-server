package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 责任链 Step 7：幂等写入向量库。
 * 先删除该文档的旧 chunk，再批量插入新 chunk，保证 MQ 重试场景下数据一致性。
 */
@Slf4j
@Order(8)
@Component
@RequiredArgsConstructor
public class PersistHandler implements IngestHandler {

    private final KnowledgeChunkRepository chunkRepository;

    @Override
    public void handle(IngestContext ctx) {
        String docId = ctx.getEvent().getDocId();
        // 幂等：先删旧 chunk，再写新 chunk，避免 MQ 重试导致重复数据
        chunkRepository.deleteByDocId(docId);
        chunkRepository.saveAll(ctx.getChunks());
        log.info("[持久化] docId={}，写入 chunk 数={}", docId, ctx.getChunks().size());
    }
}
