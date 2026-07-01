package com.aria.knowledge.infrastructure.mq;

import com.aria.knowledge.infrastructure.config.RabbitMQConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 文档摄取 RabbitMQ 消费者。
 *
 * <p>替换原 {@code IngestService + IngestWorker} 组合，
 * 用 {@link RabbitListener} 推送替代 {@code @Scheduled} 轮询，
 * 用 Spring AMQP 原生 retry / DLX 替代 Redis Streams 的 XCLAIM / PEL 手工管理。
 *
 * <p>失败处理：
 * <ul>
 *   <li>抛出异常 → Spring AMQP 自动 nack（按 yml 的 retry 配置重试 3 次）</li>
 *   <li>重试耗尽 → nack(requeue=false) → DLX → {@code knowledge.doc.ingest.dlq}</li>
 *   <li>DLQ 由 {@link DocIngestDlqHandler} 消费，将文档标记为 FAILED</li>
 * </ul>
 *
 * <p>多实例并发由 RabbitMQ 原生 Competing Consumers 模式保证，无需手动分配 WorkerId。
 */
@Slf4j
@Component
@Validated
@RequiredArgsConstructor
public class DocIngestConsumer {

    private final DocumentIngestPipeline pipeline;

    /**
     * 消费摄取队列，触发完整 pipeline。
     * 消费线程数由 {@code knowledge.ingest.consumer-concurrency} 配置控制（默认 2）。
     *
     * @param event 摄取事件 DTO（由 Jackson2JsonMessageConverter 自动反序列化）
     */
    @RabbitListener(
            queues = "${knowledge.ingest.queue}",
            concurrency = "${knowledge.ingest.consumer-concurrency:2}",
            containerFactory = RabbitMQConfig.INGEST_CONTAINER_FACTORY)
    public void consume(@Valid DocIngestEvent event) {
        log.info("[MQ:Consumer] 开始处理文档摄取 docId={} kbId={}",
                event.getDocId(), event.getKbId());
        pipeline.process(event);
        log.info("[MQ:Consumer] 文档摄取完成 docId={}", event.getDocId());
    }
}
