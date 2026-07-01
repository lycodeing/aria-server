package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.repository.KnowledgeDocRepository;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 责任链 Step 0：幂等校验。
 * 文档已处于 PUBLISHED / FAILED / DEPRECATED 终态时中断链，避免重复摄取。
 */
@Slf4j
@Order(1)
@Component
@RequiredArgsConstructor
public class IdempotencyCheckHandler implements IngestHandler {

    private final KnowledgeDocRepository docRepository;

    @Override
    public void handle(IngestContext ctx) {
        String docId = ctx.getEvent().getDocId();
        var docOpt = docRepository.findById(docId);

        if (docOpt.isEmpty()) {
            log.warn("[幂等校验] 文档记录不存在，等待 DB 提交后重试 docId={}", docId);
            throw new IllegalStateException("文档记录不存在: " + docId);
        }

        DocStatus status = docOpt.get().getStatus();
        if (status == DocStatus.PUBLISHED
                || status == DocStatus.FAILED
                || status == DocStatus.DEPRECATED) {
            log.info("[幂等校验] 文档已处于终态 status={}，跳过摄取 docId={}", status, docId);
            ctx.abort();
        }
    }
}
