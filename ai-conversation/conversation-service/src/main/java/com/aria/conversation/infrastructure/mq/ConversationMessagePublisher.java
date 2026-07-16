package com.aria.conversation.infrastructure.mq;

import com.aria.common.core.util.JsonUtils;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.MessageRole;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final String exchange;
    private final String routingKey;

    public ConversationMessagePublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${conversation.persist.exchange}") String exchange,
            @Value("${conversation.persist.routing-key}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
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
        ConversationMessage msg = new ConversationMessage(
                role, content, seq, System.currentTimeMillis(), null, null, null);
        publishMessage(sessionId, role, msg);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishMessage(String sessionId, String dbRole, ConversationMessage msg) {
        Map<String, Object> payload = new LinkedHashMap<>(12);
        payload.put(ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.MESSAGE.name());
        payload.put(ConversationStreamEvent.FIELD_SESSION_ID, sessionId);
        payload.put(ConversationStreamEvent.FIELD_ROLE, dbRole);
        payload.put(ConversationStreamEvent.FIELD_CONTENT, msg.content() != null ? msg.content() : "");
        payload.put(ConversationStreamEvent.FIELD_SEQ, msg.seq());
        payload.put(ConversationStreamEvent.FIELD_TIMESTAMP, Instant.now().getEpochSecond());
        putTraceId(payload);

        if (msg.toolRequestId() != null) {
            payload.put(ConversationStreamEvent.FIELD_TOOL_REQUEST_ID, msg.toolRequestId());
        }
        if (msg.toolName() != null) {
            payload.put(ConversationStreamEvent.FIELD_TOOL_NAME, msg.toolName());
        }
        List<ConversationMessage.ToolCall> toolCalls = msg.toolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            payload.put(ConversationStreamEvent.FIELD_TOOL_CALLS, JsonUtils.toJsonString(toolCalls));
        }

        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.debug("[MQ] MESSAGE published sessionId={} role={} seq={} hasToolCalls={}",
                sessionId, dbRole, msg.seq(), toolCalls != null && !toolCalls.isEmpty());
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionStart(String sessionId, String visitorName,
                                    String transferReason, String tag, long timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>(8);
        payload.put(ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.SESSION_START.name());
        payload.put(ConversationStreamEvent.FIELD_SESSION_ID, sessionId);
        payload.put(ConversationStreamEvent.FIELD_VISITOR_NAME, visitorName != null ? visitorName : "访客");
        payload.put(ConversationStreamEvent.FIELD_TRANSFER_REASON, transferReason != null ? transferReason : "");
        payload.put(ConversationStreamEvent.FIELD_TAG, tag != null && !tag.isBlank() ? tag : "咨询");
        payload.put(ConversationStreamEvent.FIELD_TIMESTAMP, timestamp);
        putTraceId(payload);
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_START published sessionId={}", sessionId);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionAccept(String sessionId, String agentId, long timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>(6);
        payload.put(ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.SESSION_ACCEPT.name());
        payload.put(ConversationStreamEvent.FIELD_SESSION_ID, sessionId);
        payload.put(ConversationStreamEvent.FIELD_AGENT_ID, agentId);
        payload.put(ConversationStreamEvent.FIELD_TIMESTAMP, timestamp);
        putTraceId(payload);
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_ACCEPT published sessionId={} agentId={}", sessionId, agentId);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionTransfer(String sessionId, String fromAgentId,
                                       String toAgentId, long timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>(7);
        payload.put(ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.SESSION_TRANSFER.name());
        payload.put(ConversationStreamEvent.FIELD_SESSION_ID, sessionId);
        payload.put(ConversationStreamEvent.FIELD_FROM_AGENT_ID, fromAgentId);
        payload.put(ConversationStreamEvent.FIELD_TO_AGENT_ID, toAgentId);
        payload.put(ConversationStreamEvent.FIELD_TIMESTAMP, timestamp);
        putTraceId(payload);
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_TRANSFER published sessionId={} {} → {}",
                sessionId, fromAgentId, toAgentId);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionEnd(String sessionId, String closedBy) {
        String effectiveClosedBy = ConversationStreamEvent.isValidClosedBy(closedBy)
                ? closedBy
                : ConversationStreamEvent.CLOSED_BY_SYSTEM;
        Map<String, Object> payload = new LinkedHashMap<>(6);
        payload.put(ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.SESSION_END.name());
        payload.put(ConversationStreamEvent.FIELD_SESSION_ID, sessionId);
        payload.put(ConversationStreamEvent.FIELD_TIMESTAMP, Instant.now().getEpochSecond());
        payload.put(ConversationStreamEvent.FIELD_CLOSED_BY, effectiveClosedBy);
        putTraceId(payload);
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_END published sessionId={} closedBy={}", sessionId, effectiveClosedBy);
    }

    // -------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------

    /**
     * 将当前 MDC traceId 注入 payload，非空时才写入（兼容无追踪场景）。
     */
    private void putTraceId(Map<String, Object> payload) {
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            payload.put(ConversationStreamEvent.FIELD_TRACE_ID, traceId);
        }
    }
}
