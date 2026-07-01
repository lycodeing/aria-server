package com.aidevplatform.conversation.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明。
 *
 * <p>Exchange、Queue、Binding、DLX 均以 Bean 方式声明，Spring AMQP 自动在 Broker 创建（幂等）。
 *
 * <p>拓扑结构：
 * <pre>
 *   cs.conversation（direct Exchange）
 *     ↓ routing-key: persist
 *   cs.conversation.persist（持久队列，关联 DLX）
 *     ↓ 消费失败超出 Spring AMQP retry 次数（nack + requeue=false）
 *   cs.conversation.persist.dlq（死信队列，人工排查）
 * </pre>
 *
 * <p>MessageConverter 使用 {@link Jackson2JsonMessageConverter}（JSON 格式），
 * 替代默认的 Java 序列化，消息在管理界面可读，跨版本兼容性好。
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    @Value("${conversation.persist.exchange}")    private String exchange;
    @Value("${conversation.persist.queue}")       private String queue;
    @Value("${conversation.persist.dlq}")         private String dlq;
    @Value("${conversation.persist.routing-key}") private String routingKey;
    @Value("${conversation.events.exchange}")     private String eventsExchange;

    // ----------------------------------------------------------------
    // 消息转换器
    // ----------------------------------------------------------------

    /**
     * JSON 消息转换器。
     * 替换 Spring AMQP 默认的 Java 序列化，消息以 JSON 格式存储，
     * 在 RabbitMQ 管理界面可读，跨 JVM 版本兼容。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate，注入 JSON 转换器 + Publisher ConfirmCallback。
     *
     * <p>ConfirmCallback 在 Broker 确认（ack）或拒绝（nack）消息后异步回调：
     * <ul>
     *   <li>ack=true：消息已持久化到 Broker 磁盘，发布端可靠性保障完成</li>
     *   <li>ack=false：Broker 拒绝，打印 ERROR 日志，发布端 @Retryable 已耗尽，需人工介入</li>
     * </ul>
     *
     * <p>需要在 yml 配置 {@code spring.rabbitmq.publisher-confirm-type: correlated}，
     * 否则 ConfirmCallback 不会被触发。
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        // Publisher Confirms 回调：Broker 持久化确认 / 拒绝时触发
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("[MQ] Publisher Confirm NACK，消息被 Broker 拒绝，cause={}, correlationData={}",
                        cause, correlationData);
            }
        });
        // Publisher Returns 回调：消息路由到 Exchange 但找不到队列时触发
        template.setReturnsCallback(returned ->
            log.error("[MQ] Message returned，无法路由到队列: exchange={} routingKey={} replyCode={} replyText={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText())
        );
        template.setMandatory(true);
        return template;
    }

    // ----------------------------------------------------------------
    // 拓扑声明
    // ----------------------------------------------------------------

    /** 主 Exchange（direct 类型，路由精确匹配 routing key） */
    @Bean
    public DirectExchange conversationExchange() {
        return ExchangeBuilder.directExchange(exchange).durable(true).build();
    }

    /**
     * 死信队列（DLQ）。
     * 消费失败超出 Spring AMQP retry 次数（nack + requeue=false）后，
     * 消息通过 DLX 自动路由到此队列，运维人员可在管理界面查看并手动重放。
     */
    @Bean
    public Queue conversationDlq() {
        return QueueBuilder.durable(dlq).build();
    }

    /**
     * 主持久化队列，绑定 DLX。
     * 消息被 nack（requeue=false）时，自动路由到 {@link #conversationDlq()}。
     * 消息本身持久化（Spring AMQP 默认 delivery-mode=2）。
     */
    @Bean
    public Queue conversationPersistQueue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", "")   // 使用默认 Exchange
                .withArgument("x-dead-letter-routing-key", dlq)
                .build();
    }

    /** 绑定主队列到 Exchange（routing key = persist） */
    @Bean
    public Binding conversationBinding() {
        return BindingBuilder.bind(conversationPersistQueue())
                .to(conversationExchange())
                .with(routingKey);
    }

    /**
     * 事件广播专用 RabbitTemplate（fire-and-forget 语义）。
     *
     * <p>与持久化 {@link #rabbitTemplate} 分离的原因：
     * <ul>
     *   <li>{@code mandatory=false}（默认）：没有消费者绑定时静默丢弃，不触发 ReturnsCallback，
     *       避免在 Pod 启动或最后一个 SSE 断开后刷出误导性 ERROR 日志</li>
     *   <li>不开启 Publisher Confirms：事件是实时推送，丢失可接受（agent 断连后靠 REST 补齐状态），
     *       无需阻塞等待 Broker ACK</li>
     * </ul>
     */
    @Bean("eventsRabbitTemplate")
    public RabbitTemplate eventsRabbitTemplate(ConnectionFactory connectionFactory,
                                                MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        // mandatory = false（默认）— 无消费者时静默丢弃，不回调 ReturnsCallback
        return template;
    }

    // ----------------------------------------------------------------
    // 事件广播拓扑（Fanout）
    // ----------------------------------------------------------------

    /**
     * 会话事件广播 Exchange（fanout 类型）。
     * SessionQueueService 向此 exchange 发布队列变更事件（ENQUEUE / ACCEPTED / CLOSED），
     * 每个座席 SSE 连接各自绑定一个临时独占队列，实现广播。
     *
     * <p>与持久化 Exchange（direct）分离，职责清晰：
     * <ul>
     *   <li>cs.conversation（direct）→ 持久化链路，写 DB</li>
     *   <li>cs.conversation.events（fanout）→ 实时通知链路，推 SSE</li>
     * </ul>
     */
    @Bean
    public FanoutExchange conversationEventsExchange() {
        return ExchangeBuilder.fanoutExchange(eventsExchange).durable(true).build();
    }
}
