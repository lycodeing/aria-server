package com.aidevplatform.knowledge.infrastructure.mq;

import com.aidevplatform.knowledge.domain.model.DocStatus;
import com.aidevplatform.knowledge.domain.repository.KnowledgeDocRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 死信队列处理器。
 *
 * <p>主队列 {@code DocIngestConsumer} 重试耗尽（默认 3 次）后，
 * 消息被路由到 {@code knowledge.doc.ingest.dlq}，由本 Handler 消费并：
 * <ol>
 *   <li>更新文档状态为 {@link DocStatus#FAILED}（对齐原 IngestService.markDocFailed 行为）</li>
 *   <li>打印 ERROR 日志供运维人员排查</li>
 * </ol>
 *
 * <p>DLQ 消费失败不再重试（避免无限循环），仅记录日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocIngestDlqHandler {

    private final KnowledgeDocRepository docRepository;

    /**
     * 消费 DLQ 中的失败摄取事件，标记文档为 FAILED。
     */
    @RabbitListener(queues = "${knowledge.ingest.dlq}", concurrency = "1")
    public void handleDlq(DocIngestEvent event) {
        try {
            log.error("[DLQ] 文档摄取最终失败 docId={} kbId={} storagePath={}",
                    event.getDocId(), event.getKbId(), event.getStoragePath());
            docRepository.updateStatusBatch(List.of(event.getDocId()), DocStatus.FAILED);
        } catch (Exception e) {
            log.error("[DLQ] 标记文档 FAILED 失败 docId={}", event.getDocId(), e);
        }
    }
}
