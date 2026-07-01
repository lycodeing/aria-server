package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.repository.KnowledgeDocRepository;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 责任链 Step 8：将文档状态更新为 PUBLISHED，完成摄取流程。
 * 使用状态模式：通过 DocStatus.transitionTo() 校验流转合法性。
 */
@Slf4j
@Order(9)
@Component
@RequiredArgsConstructor
public class StatusUpdateHandler implements IngestHandler {

    private final KnowledgeDocRepository docRepository;

    @Override
    public void handle(IngestContext ctx) {
        String docId = ctx.getEvent().getDocId();
        // 状态模式：DRAFT → PUBLISHED，非法流转时 transitionTo 会抛 5010
        docRepository.findById(docId).ifPresent(doc ->
            doc.getStatus().transitionTo(DocStatus.PUBLISHED)
        );
        docRepository.updateStatusBatch(List.of(docId), DocStatus.PUBLISHED);
        log.info("[状态更新] docId={} 摄取完成，状态已更新为 PUBLISHED", docId);
    }
}
