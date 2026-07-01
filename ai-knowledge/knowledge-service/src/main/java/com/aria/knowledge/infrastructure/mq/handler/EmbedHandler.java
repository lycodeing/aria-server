package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.infrastructure.embedding.EmbeddingService;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 责任链 Step 6：批量向量化（BGE-M3），就地填充 KnowledgeChunk.vector 字段。
 */
@Slf4j
@Order(7)
@Component
@RequiredArgsConstructor
public class EmbedHandler implements IngestHandler {

    private final EmbeddingService embeddingService;

    @Override
    public void handle(IngestContext ctx) {
        log.debug("[向量化] docId={}，待向量化 chunk 数={}", 
            ctx.getEvent().getDocId(), ctx.getChunks().size());
        embeddingService.embed(ctx.getChunks());
        log.debug("[向量化] docId={} 向量化完成", ctx.getEvent().getDocId());
    }
}
