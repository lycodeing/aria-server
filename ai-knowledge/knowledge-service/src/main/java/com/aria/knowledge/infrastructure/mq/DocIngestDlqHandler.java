package com.aria.knowledge.infrastructure.mq;

import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.repository.KnowledgeDocRepository;
import com.aria.knowledge.infrastructure.config.RabbitMQConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

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
 * <p>容器工厂 {@link RabbitMQConfig#DLQ_CONTAINER_FACTORY} 禁用 retry，
 * 失败时通过 {@link AmqpRejectAndDontRequeueException} 显式丢弃消息，
 * 避免与主队列的 retry 策略叠加导致无限循环。
 *
 * <p>异常处理策略（阿里规范：分类处理）：
 * <ul>
 *   <li>DB 异常 → ERROR 日志 + 抛 {@link AmqpRejectAndDontRequeueException}，消息丢弃，
 *       需运维通过日志告警人工补偿（生产环境建议写 parking-lot 表）</li>
 *   <li>非预期异常 → 同样丢弃避免阻塞 DLQ，但 ERROR 级日志可触发监控告警</li>
 * </ul>
 */
@Slf4j
@Component
@Validated
@RequiredArgsConstructor
public class DocIngestDlqHandler {

    private final KnowledgeDocRepository docRepository;

    /**
     * 消费 DLQ 中的失败摄取事件，标记文档为 FAILED。
     *
     * @param event 失败的摄取事件
     * @throws AmqpRejectAndDontRequeueException DB 操作失败时抛出，告知 Spring AMQP 直接丢弃不重入队
     */
    @RabbitListener(
            queues = "${knowledge.ingest.dlq}",
            concurrency = "${knowledge.ingest.dlq-concurrency:1}",
            containerFactory = RabbitMQConfig.DLQ_CONTAINER_FACTORY)
    public void handleDlq(@Valid DocIngestEvent event) {
        log.error("[MQ:DLQ] 文档摄取最终失败 docId={} kbId={} storagePath={}",
                event.getDocId(), event.getKbId(), event.getStoragePath());
        try {
            docRepository.updateStatusBatch(List.of(event.getDocId()), DocStatus.FAILED);
        } catch (DataAccessException e) {
            // DB 不可用：日志告警 + 丢弃消息，避免在 DLQ 循环占用资源；运维需介入
            log.error("[MQ:DLQ] DB 标记 FAILED 失败，请人工补偿 docId={}", event.getDocId(), e);
            throw new AmqpRejectAndDontRequeueException(
                    "DLQ Handler DB 操作失败 docId=" + event.getDocId(), e);
        }
    }
}
