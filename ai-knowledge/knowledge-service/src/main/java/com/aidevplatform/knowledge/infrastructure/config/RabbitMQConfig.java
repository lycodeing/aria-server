package com.aidevplatform.knowledge.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
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

    /** 主队列消费容器工厂 Bean 名 */
    public static final String INGEST_CONTAINER_FACTORY = "ingestRabbitListenerContainerFactory";
    /** DLQ 消费容器工厂 Bean 名（禁用 retry，失败直接丢弃避免无限循环） */
    public static final String DLQ_CONTAINER_FACTORY    = "dlqRabbitListenerContainerFactory";

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
                log.error("[MQ:Publisher] Confirm NACK，消息被 Broker 拒绝，cause={}, correlationData={}",
                        cause, correlationData);
            }
        });
        template.setReturnsCallback(returned ->
            log.error("[MQ:Publisher] 消息无法路由到队列: exchange={} routingKey={} replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText())
        );
        template.setMandatory(true);
        return template;
    }

    /**
     * 主队列容器工厂：启用 retry（由 yml 控制 max-attempts=3），失败后 nack→DLX。
     */
    @Bean(INGEST_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory ingestRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        // 失败 → nack(requeue=false) → DLX（与 yml retry 配合）
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    /**
     * DLQ 容器工厂：禁用 retry，失败直接丢弃（避免无限循环）。
     * DLQ 是最后兜底，本身失败时应由 parking-lot 表或人工排查处理。
     */
    @Bean(DLQ_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory dlqRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setDefaultRequeueRejected(false);
        // 显式不开启 retryTemplate：DLQ Handler 自身负责异常处理，不再重试
        return factory;
    }

    // ----------------------------------------------------------------
    // 拓扑声明
    // ----------------------------------------------------------------

    /** 主 Exchange（direct 类型，路由精确匹配 routing key） */
    @Bean
    public DirectExchange knowledgeIngestExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    /** 死信队列（DLQ） */
    @Bean
    public Queue knowledgeIngestDlq() {
        return QueueBuilder.durable(dlq).build();
    }

    /** 主持久化队列，绑定 DLX（使用默认 Exchange 路由到 DLQ） */
    @Bean
    public Queue knowledgeIngestQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", dlq)
                .build();
    }

    /** 绑定主队列到 Exchange，routing key = ingest */
    @Bean
    public Binding knowledgeIngestBinding(Queue knowledgeIngestQueue,
                                          DirectExchange knowledgeIngestExchange) {
        return BindingBuilder.bind(knowledgeIngestQueue)
                .to(knowledgeIngestExchange)
                .with(routingKey);
    }
}
