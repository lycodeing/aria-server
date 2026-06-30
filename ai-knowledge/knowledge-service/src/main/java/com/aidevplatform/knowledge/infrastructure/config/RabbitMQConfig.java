package com.aidevplatform.knowledge.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库 RabbitMQ 拓扑声明。
 *
 * <p>替换原 Redis Streams 文档摄取队列，遵循"Redis 只做缓存+锁"的职责分离原则。
 *
 * <p>拓扑结构：
 * <pre>
 *   knowledge.doc.ingest（direct Exchange）
 *     ↓ routing-key: ingest
 *   knowledge.doc.ingest.queue（持久队列，关联 DLX）
 *     ↓ 消费失败超出 Spring AMQP retry 次数（nack + requeue=false）
 *   knowledge.doc.ingest.dlq（死信队列，由 DocIngestDlqHandler 消费并标记 FAILED）
 * </pre>
 *
 * <p>消息体使用 {@link Jackson2JsonMessageConverter} 序列化为 JSON，
 * 在 RabbitMQ 管理界面可读，跨版本兼容。
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    @Value("${knowledge.ingest.exchange}")    private String exchange;
    @Value("${knowledge.ingest.queue}")       private String queue;
    @Value("${knowledge.ingest.dlq}")         private String dlq;
    @Value("${knowledge.ingest.routing-key}") private String routingKey;

    /** JSON 消息转换器，替换 Spring AMQP 默认的 Java 序列化 */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate，注入 JSON 转换器 + Publisher Confirms / Returns 回调。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("[MQ] Publisher Confirm NACK，消息被 Broker 拒绝，cause={}, correlationData={}",
                        cause, correlationData);
            }
        });
        template.setReturnsCallback(returned ->
            log.error("[MQ] 消息无法路由到队列: exchange={} routingKey={} replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText())
        );
        template.setMandatory(true);
        return template;
    }

    // ----------------------------------------------------------------
    // 拓扑声明
    // ----------------------------------------------------------------

    /** 主 Exchange（direct 类型，路由精确匹配 routing key） */
    @Bean
    public DirectExchange knowledgeIngestExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    /**
     * 死信队列（DLQ）。
     * 消费失败超出 retry 次数后，消息通过默认 Exchange 路由到此队列。
     */
    @Bean
    public Queue knowledgeIngestDlq() {
        return QueueBuilder.durable(dlq).build();
    }

    /**
     * 主持久化队列，绑定 DLX（使用默认 Exchange 路由到 DLQ）。
     */
    @Bean
    public Queue knowledgeIngestQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", dlq)
                .build();
    }

    /** 绑定主队列到 Exchange，routing key = ingest */
    @Bean
    public Binding knowledgeIngestBinding() {
        return BindingBuilder.bind(knowledgeIngestQueue())
                .to(knowledgeIngestExchange())
                .with(routingKey);
    }
}
