package com.aria.knowledge.infrastructure.mq;

import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.repository.KnowledgeDocRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档摄取事件 RabbitMQ 发布者。
 *
 * <p>替换原 {@code RedisStreamHelper.publish()}，
 * 通过 {@code knowledge.doc.ingest} Direct Exchange 异步派发文档摄取任务。
 *
 * <p>可靠性保障：
 * <ul>
 *   <li>{@link Retryable}：发布异常时本地重试 3 次（指数退避）</li>
 *   <li>{@link Recover}：3 次重试耗尽后兜底，将文档标记为 FAILED，避免幽灵文档</li>
 *   <li>Publisher Confirms（{@link RabbitTemplate}）：Broker 持久化确认</li>
 *   <li>Mandatory + ReturnsCallback：消息无法路由时打印 ERROR 日志</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocIngestPublisher {

    private final RabbitTemplate         rabbitTemplate;
    private final KnowledgeDocRepository docRepository;

    @Value("${knowledge.ingest.exchange}")    private String exchange;
    @Value("${knowledge.ingest.routing-key}") private String routingKey;

    /**
     * 发布文档摄取事件，失败本地重试 3 次。
     *
     * @param event 摄取事件 DTO，由 RabbitTemplate JSON 序列化后发送
     */
    @Retryable(retryFor = AmqpException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publish(DocIngestEvent event) {
        rabbitTemplate.convertAndSend(exchange, routingKey, event);
        log.info("[MQ:Publisher] 文档摄取事件已发布 docId={} kbId={}", event.getDocId(), event.getKbId());
    }

    /**
     * 重试耗尽后的兜底逻辑：将文档直接标记为 FAILED，避免出现"DB 有记录但永远无 chunk"的幽灵文档。
     *
     * <p>阿里规约：对失败的代码加重试时，需要考虑最终失败的兜底逻辑。
     */
    @Recover
    public void recover(AmqpException ex, DocIngestEvent event) {
        log.error("[MQ:Publisher] 发布耗尽 3 次重试，标记文档为 FAILED docId={}",
                event.getDocId(), ex);
        try {
            docRepository.updateStatusBatch(List.of(event.getDocId()), DocStatus.FAILED);
        } catch (Exception dbEx) {
            // DB 兜底失败：仅 ERROR 日志，需要运维通过日志告警人工补偿
            log.error("[MQ:Publisher] 兜底标记 FAILED 失败 docId={}", event.getDocId(), dbEx);
        }
    }
}
