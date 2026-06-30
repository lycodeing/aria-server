package com.aidevplatform.knowledge.infrastructure.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * 文档摄取事件 RabbitMQ 发布者。
 *
 * <p>替换原 {@code RedisStreamHelper.publish()}，
 * 通过 {@code knowledge.doc.ingest} Direct Exchange 异步派发文档摄取任务。
 *
 * <p>可靠性保障：
 * <ul>
 *   <li>{@link Retryable}：发布异常时本地重试 3 次（指数退避）</li>
 *   <li>Publisher Confirms（{@link RabbitTemplate}）：Broker 持久化确认</li>
 *   <li>Mandatory + ReturnsCallback：消息无法路由时打印 ERROR 日志</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocIngestPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${knowledge.ingest.exchange}")    private String exchange;
    @Value("${knowledge.ingest.routing-key}") private String routingKey;

    /**
     * 发布文档摄取事件。
     *
     * @param event 摄取事件 DTO，由 RabbitTemplate JSON 序列化后发送
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publish(DocIngestEvent event) {
        rabbitTemplate.convertAndSend(exchange, routingKey, event);
        log.info("[MQ] 文档摄取事件已发布 docId={} kbId={}", event.getDocId(), event.getKbId());
    }
}
