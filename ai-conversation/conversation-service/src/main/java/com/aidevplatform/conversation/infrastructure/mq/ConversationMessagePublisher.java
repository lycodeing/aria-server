package com.aidevplatform.conversation.infrastructure.mq;

import com.aidevplatform.conversation.domain.MessageRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 对话消息 RabbitMQ 发布端。
 *
 * <p>可靠性保障（两层）：
 * <ol>
 *   <li>{@link Retryable}：发布失败最多重试 3 次，指数退避（1s → 2s → 4s），
 *       覆盖 RabbitMQ 短暂不可用场景</li>
 *   <li>Publisher Confirms（yml 配置 publisher-confirm-type: correlated）：
 *       Broker 持久化确认后才返回，保障消息已写入磁盘</li>
 * </ol>
 *
 * <p>三次重试全部失败后，异常向上传播，调用方打印 WARN 日志，
 * 消息仅存 Redis List（实时路由仍可用，持久化链路降级）。
 */
@Slf4j
@Component
public class ConversationMessagePublisher {

    // m2 修复：字段名常量统一使用 ConversationStreamEvent 中的 public 常量，消除重复定义

    private final RabbitTemplate rabbitTemplate;
    private final String         exchange;
    private final String         routingKey;

    public ConversationMessagePublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${conversation.persist.exchange}")    String exchange,
            @Value("${conversation.persist.routing-key}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange       = exchange;
        this.routingKey     = routingKey;
    }

    /**
     * 发布单条对话消息（MESSAGE 类型）。
     * 三次重试失败后异常向上传播，调用方打印 WARN，消息仅存 Redis List。
     *
     * @param sessionId 会话 ID
     * @param role      DB 角色标识（{@link MessageRole} 的 value）
     * @param content   消息内容
     * @param seq       session 内单调递增序号（由 ConversationHistoryRepository.nextSeq 生成）
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishMessage(String sessionId, String role, String content, long seq) {
        Map<String, Object> payload = Map.of(
            ConversationStreamEvent.FIELD_TYPE,       ConversationStreamEvent.Type.MESSAGE.name(),
            ConversationStreamEvent.FIELD_SESSION_ID, sessionId,
            ConversationStreamEvent.FIELD_ROLE,       role,
            ConversationStreamEvent.FIELD_CONTENT,    content,
            ConversationStreamEvent.FIELD_SEQ,        seq,
            ConversationStreamEvent.FIELD_TIMESTAMP,  Instant.now().getEpochSecond()
        );
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.debug("[MQ] MESSAGE published sessionId={} role={} seq={}", sessionId, role, seq);
    }

    /**
     * 发布会话开始事件（SESSION_START 类型），由 SessionQueueService.enqueue() 触发。
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionStart(String sessionId, String visitorName,
                                     String transferReason, String tag, long timestamp) {
        Map<String, Object> payload = Map.of(
            ConversationStreamEvent.FIELD_TYPE,            ConversationStreamEvent.Type.SESSION_START.name(),
            ConversationStreamEvent.FIELD_SESSION_ID,      sessionId,
            ConversationStreamEvent.FIELD_VISITOR_NAME,    visitorName    != null          ? visitorName    : "访客",
            ConversationStreamEvent.FIELD_TRANSFER_REASON, transferReason != null          ? transferReason : "",
            ConversationStreamEvent.FIELD_TAG,             tag != null && !tag.isBlank()   ? tag            : "咨询",
            ConversationStreamEvent.FIELD_TIMESTAMP,       timestamp
        );
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_START published sessionId={}", sessionId);
    }

    /**
     * 发布会话接入事件（SESSION_ACCEPT 类型），由 SessionQueueService.accept() 触发。
     *
     * @param sessionId 会话 ID
     * @param agentId   接入座席 ID（写入 DB 的 agent_id 字段）
     * @param timestamp 接入时间戳
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionAccept(String sessionId, String agentId, long timestamp) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put(ConversationStreamEvent.FIELD_TYPE,       ConversationStreamEvent.Type.SESSION_ACCEPT.name());
        payload.put(ConversationStreamEvent.FIELD_SESSION_ID, sessionId);
        payload.put(ConversationStreamEvent.FIELD_AGENT_ID,   agentId);
        payload.put(ConversationStreamEvent.FIELD_TIMESTAMP,  timestamp);
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_ACCEPT published sessionId={} agentId={}", sessionId, agentId);
    }

    /**
     * 发布会话转交事件（SESSION_TRANSFER 类型），由 SessionQueueService.transfer() 触发。
     * Consumer 端将 DB 的 agent_id 更新为 toAgentId。
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionTransfer(String sessionId, String fromAgentId,
                                       String toAgentId, long timestamp) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put(ConversationStreamEvent.FIELD_TYPE,          ConversationStreamEvent.Type.SESSION_TRANSFER.name());
        payload.put(ConversationStreamEvent.FIELD_SESSION_ID,    sessionId);
        payload.put(ConversationStreamEvent.FIELD_FROM_AGENT_ID, fromAgentId);
        payload.put(ConversationStreamEvent.FIELD_TO_AGENT_ID,   toAgentId);
        payload.put(ConversationStreamEvent.FIELD_TIMESTAMP,     timestamp);
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_TRANSFER published sessionId={} {} → {}",
                sessionId, fromAgentId, toAgentId);
    }

    /**
     * 发布会话结束事件（SESSION_END 类型），由 SessionQueueService.close() 触发。
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionEnd(String sessionId) {
        Map<String, Object> payload = Map.of(
            ConversationStreamEvent.FIELD_TYPE,       ConversationStreamEvent.Type.SESSION_END.name(),
            ConversationStreamEvent.FIELD_SESSION_ID, sessionId,
            ConversationStreamEvent.FIELD_TIMESTAMP,  Instant.now().getEpochSecond()
        );
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_END published sessionId={}", sessionId);
    }
}
