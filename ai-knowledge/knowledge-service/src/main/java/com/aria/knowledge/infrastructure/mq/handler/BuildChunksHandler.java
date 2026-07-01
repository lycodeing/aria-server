package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.common.core.util.IdGenerator;
import com.aria.common.core.util.TokenUtils;
import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import com.aria.knowledge.infrastructure.splitter.SplitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 责任链 Step 5：将合格切片构建为 KnowledgeChunk 领域对象，注入元数据。
 * 从 {@link IngestContext#qualifiedSplits} 读取，结果写入 {@link IngestContext#chunks}。
 */
@Slf4j
@Order(6)
@Component
public class BuildChunksHandler implements IngestHandler {

    @Override
    public void handle(IngestContext ctx) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        String docId = ctx.getEvent().getDocId();
        String kbId  = ctx.getEvent().getKbId();

        for (SplitResult split : ctx.getQualifiedSplits()) {
            chunks.add(KnowledgeChunk.builder()
                .id(String.valueOf(IdGenerator.nextId()))
                .docId(docId)
                .kbId(kbId)
                .docStatus(DocStatus.PUBLISHED.name())
                .content(split.getContent())
                .tokenCount(TokenUtils.estimate(split.getContent()))
                .retrievalWeight(BigDecimal.ONE)
                .feedbackDownvotes(0)
                // 来自 ParsedDocument 的结构元数据
                .pageNum(split.getPageNum())
                .sectionTitle(split.getSectionTitle())
                .chunkType(split.getChunkType())
                .build());
        }
        ctx.setChunks(chunks);
        log.debug("[构建Chunk] docId={}，chunk 数={}", docId, chunks.size());
    }
}
