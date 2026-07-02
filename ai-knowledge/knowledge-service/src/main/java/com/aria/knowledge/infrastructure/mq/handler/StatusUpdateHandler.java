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
 * 责任链 Step 8：将文档状态更新为 PUBLISHED，完成摄取流程。
 *
 * <p>使用带条件的原子 UPDATE（{@link KnowledgeDocRepository#updateStatusIfDraft}），
 * 替代原来"findById + updateStatusBatch"的 TOCTOU 双步操作：
 * <ul>
 *   <li>原子性：DB 层 WHERE status='DRAFT' 保证读-校验-写不被其他进程插入</li>
 *   <li>文档不存在 / 非 DRAFT 状态：UPDATE 影响行数为 0，记录 WARN 但不抛异常</li>
 * </ul>
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
        // 带条件单次 UPDATE：WHERE status='DRAFT'，消除 TOCTOU 竞态。
        // ⚠️ 此处刻意绕过 DocStatus.transitionTo() 状态机，直接使用 DB 条件 UPDATE，
        // 原因：Pipeline 执行时文档处于 DRAFT 状态，transitionTo(PUBLISHED) 合法但需先 findById；
        // 改用 updateStatusIfDraft 单次原子操作减少一次 DB 查询并消除并发写竞态。
        // 如需修改流转逻辑，请同步更新 DocStatus.DRAFT.allowedTransitions()。
        int affected = docRepository.updateStatusIfDraft(docId, DocStatus.PUBLISHED);
        if (affected == 0) {
            log.warn("[状态更新] 文档不存在或非 DRAFT 状态，跳过 PUBLISHED 更新 docId={}", docId);
        } else {
            log.info("[状态更新] docId={} 摄取完成，状态已更新为 PUBLISHED", docId);
        }
    }
}
