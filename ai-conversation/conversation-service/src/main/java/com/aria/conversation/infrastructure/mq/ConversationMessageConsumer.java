package com.aria.conversation.infrastructure.mq;

import com.aria.conversation.domain.MessageRole;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 对话消息 RabbitMQ 消费者。
 *
 * <p>替换原 {@code ConversationStreamWorker} + {@code ConversationPersistenceService}，
 * PEL/XCLAIM 复杂逻辑全部由 Spring AMQP + RabbitMQ 原生机制替代：
 * <ul>
 *   <li>消费失败自动 nack → Spring AMQP Retry（3次）→ DLX → {@code cs.conversation.persist.dlq}</li>
 *   <li>Broker 崩溃时，未 ack 消息重新入队，重启后继续消费</li>
 * </ul>
 *
 * <p>全链路追踪：从 payload {@link ConversationStreamEvent#FIELD_TRACE_ID} 恢复 MDC traceId，
 * 使异步消费日志与触发请求保持相同 traceId。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationMessageConsumer {

    private static final String MDC_TRACE_ID = "traceId";

    private final ConversationPersistRepository persistRepository;

    /**
     * 消费持久化队列中的消息，按事件类型分发到对应处理方法。
     * 抛出异常时 Spring AMQP 自动 nack，进入重试流程。
     *
     * @param payload MQ 消息体（Map 格式，key 均为字符串）
     */
    @RabbitListener(queues = "${conversation.persist.queue}", concurrency = "2")
    public void consume(Map<String, Object> payload) {
        // 从消息体恢复 traceId 到 MDC，保证消费端日志与触发请求同一 traceId
        String traceId = str(payload, ConversationStreamEvent.FIELD_TRACE_ID);
        boolean traceRestored = traceId != null && !traceId.isBlank();
        if (traceRestored) {
            MDC.put(MDC_TRACE_ID, traceId);
        }

        try {
            String type = str(payload, ConversationStreamEvent.FIELD_TYPE);
            String sessionId = str(payload, ConversationStreamEvent.FIELD_SESSION_ID);
            log.debug("[MQ Consumer] 处理消息 type={} sessionId={}", type, sessionId);

            if (type == null) {
                log.warn("[MQ Consumer] 消息缺少 type 字段，丢弃 sessionId={}", sessionId);
                return;
            }

            ConversationStreamEvent.Type eventType;
            try {
                eventType = ConversationStreamEvent.Type.valueOf(type);
            } catch (IllegalArgumentException e) {
                log.warn("[MQ Consumer] 未知事件类型，直接 ACK 丢弃（避免毒消息阻塞队列）type={} sessionId={}", type, sessionId);
                return;
            }

            switch (eventType) {
                case SESSION_START    -> handleSessionStart(payload, sessionId);
                case SESSION_ACCEPT   -> handleSessionAccept(payload, sessionId);
                case SESSION_TRANSFER -> handleSessionTransfer(payload, sessionId);
                case SESSION_END      -> handleSessionEnd(payload, sessionId);
                case MESSAGE          -> handleMessage(payload, sessionId);
            }
        } finally {
            if (traceRestored) {
                MDC.remove(MDC_TRACE_ID);
            }
        }
    }

    // -------------------------------------------------------
    // 事件处理方法
    // -------------------------------------------------------

    /**
     * 处理转人工事件：将 AI_CHAT 会话升级为 WAITING 状态。
     *
     * <p>新设计下转人工时会话一定已通过 /session/init 接口创建，
     * 因此直接 UPDATE，无需 insert 兜底。upgradeToWaiting 返回 0 时会打印 warn 日志。
     */
    private void handleSessionStart(Map<String, Object> payload, String sessionId) {
        persistRepository.upgradeToWaiting(
                sessionId,
                str(payload, ConversationStreamEvent.FIELD_VISITOR_NAME),
                str(payload, ConversationStreamEvent.FIELD_TRANSFER_REASON),
                str(payload, ConversationStreamEvent.FIELD_TAG),
                toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP)));
    }

    /**
     * 处理 SESSION_ACCEPT：更新会话状态为 ACTIVE，写入接入座席 ID。
     */
    private void handleSessionAccept(Map<String, Object> payload, String sessionId) {
        persistRepository.activateConversation(
                sessionId,
                str(payload, ConversationStreamEvent.FIELD_AGENT_ID),
                toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP)));
    }

    /**
     * 处理 SESSION_TRANSFER：更新 DB 中的 agent_id 为目标座席。
     */
    private void handleSessionTransfer(Map<String, Object> payload, String sessionId) {
        persistRepository.transferConversation(
                sessionId,
                str(payload, ConversationStreamEvent.FIELD_TO_AGENT_ID));
    }

    /**
     * 处理 SESSION_END：更新会话状态为 CLOSED，记录结束时间和关闭发起方。
     */
    private void handleSessionEnd(Map<String, Object> payload, String sessionId) {
        String closedBy = str(payload, ConversationStreamEvent.FIELD_CLOSED_BY);
        if (!ConversationStreamEvent.isValidClosedBy(closedBy)) {
            log.warn("[MQ Consumer] 非法 closedBy={}，降级为 system sessionId={}", closedBy, sessionId);
            closedBy = ConversationStreamEvent.CLOSED_BY_SYSTEM;
        }
        persistRepository.closeConversation(
                sessionId,
                toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP)),
                closedBy);
    }

    /**
     * 处理 MESSAGE：写入消息明细。
     * role 非法时 ACK 丢弃，避免 DB NOT NULL 约束触发无限重试。
     */
    private void handleMessage(Map<String, Object> payload, String sessionId) {
        String roleStr = str(payload, ConversationStreamEvent.FIELD_ROLE);
        MessageRole role = MessageRole.fromValue(roleStr);
        if (role == null) {
            log.warn("[MQ Consumer] 未知 role={}，消息 ACK 丢弃（避免 DB NOT NULL 约束炸队列）sessionId={}", roleStr, sessionId);
            return;
        }
        ConversationMessageEntity entity = new ConversationMessageEntity();
        entity.setSessionId(sessionId);
        entity.setRole(role);
        String content = str(payload, ConversationStreamEvent.FIELD_CONTENT);
        entity.setContent(content != null ? content : "");
        Object rawSeq = payload.get(ConversationStreamEvent.FIELD_SEQ);
        if (rawSeq != null) {
            try {
                entity.setSeq(Long.parseLong(rawSeq.toString()));
            } catch (NumberFormatException e) {
                log.warn("[MQ Consumer] 非法 seq={} sessionId={}", rawSeq, sessionId);
            }
        }
        OffsetDateTime msgTime = toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP));
        entity.setCreatedAt(msgTime);
        entity.setToolRequestId(str(payload, ConversationStreamEvent.FIELD_TOOL_REQUEST_ID));
        entity.setToolName(str(payload, ConversationStreamEvent.FIELD_TOOL_NAME));
        entity.setToolCallsJson(str(payload, ConversationStreamEvent.FIELD_TOOL_CALLS));
        persistRepository.saveMessages(List.of(entity));

        // 首条 agent 消息：幂等写入 first_reply_at（仅在 first_reply_at 为 NULL 时才更新）
        if (role == MessageRole.AGENT) {
            persistRepository.setFirstReplyAtIfAbsent(sessionId, msgTime);
        }
    }

    // -------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------

    /**
     * 安全获取 Map 中的字符串值，键不存在时返回 null
     */
    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * 安全获取 Map 中的 long 值，键不存在或格式非法时返回当前时间戳
     */
    private long longVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return Instant.now().getEpochSecond();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return Instant.now().getEpochSecond();
        }
    }

    /**
     * epoch seconds → OffsetDateTime（UTC）
     */
    private OffsetDateTime toOffsetDateTime(long epochSeconds) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }
}
