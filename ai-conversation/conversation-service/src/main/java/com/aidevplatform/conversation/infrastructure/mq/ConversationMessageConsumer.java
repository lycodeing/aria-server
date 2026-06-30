package com.aidevplatform.conversation.infrastructure.mq;

import com.aidevplatform.conversation.domain.MessageRole;
import com.aidevplatform.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aidevplatform.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>幂等性保障：
 * <ul>
 *   <li>SESSION_START：{@code ConversationPersistRepository.startConversation()} 捕获 unique key 冲突</li>
 *   <li>MESSAGE：按 sessionId + created_at 追加，极端重复场景可接受少量重复行</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationMessageConsumer {

    private final ConversationPersistRepository persistRepository;

    /**
     * 消费持久化队列中的消息，按事件类型分发到对应处理方法。
     * 抛出异常时 Spring AMQP 自动 nack，进入重试流程。
     *
     * @param payload MQ 消息体（Map 格式，key 均为字符串）
     */
    @RabbitListener(queues = "${conversation.persist.queue}", concurrency = "2")
    public void consume(Map<String, Object> payload) {
        String type      = str(payload, ConversationStreamEvent.FIELD_TYPE);
        String sessionId = str(payload, ConversationStreamEvent.FIELD_SESSION_ID);
        log.debug("[MQ Consumer] 处理消息 type={} sessionId={}", type, sessionId);

        if (type == null) {
            log.warn("[MQ Consumer] 消息缺少 type 字段，丢弃 sessionId={}", sessionId);
            return;
        }

        // I2 修复：捕获 valueOf 的 IllegalArgumentException，防止未知类型造成毒消息死循环
        ConversationStreamEvent.Type eventType;
        try {
            eventType = ConversationStreamEvent.Type.valueOf(type);
        } catch (IllegalArgumentException e) {
            log.warn("[MQ Consumer] 未知事件类型，直接 ACK 丢弃（避免毒消息阻塞队列）type={} sessionId={}", type, sessionId);
            return;
        }

        // 【强制】用枚举比较，消除字符串魔法值
        switch (eventType) {
            case SESSION_START    -> handleSessionStart(payload, sessionId);
            case SESSION_ACCEPT   -> handleSessionAccept(payload, sessionId);
            case SESSION_TRANSFER -> handleSessionTransfer(payload, sessionId);
            case SESSION_END      -> handleSessionEnd(payload, sessionId);
            case MESSAGE          -> handleMessage(payload, sessionId);
        }
    }

    // -------------------------------------------------------
    // 事件处理方法
    // -------------------------------------------------------

    /**
     * 处理 SESSION_START：在 DB 创建会话记录。
     * 幂等：同一 sessionId 重复消费时静默忽略（unique key 冲突）。
     */
    private void handleSessionStart(Map<String, Object> payload, String sessionId) {
        persistRepository.startConversation(
            sessionId,
            str(payload, ConversationStreamEvent.FIELD_VISITOR_NAME),
            str(payload, ConversationStreamEvent.FIELD_TRANSFER_REASON),
            str(payload, ConversationStreamEvent.FIELD_TAG),
            toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP)));
    }

    /**
     * 处理 SESSION_ACCEPT：将 DB 中的会话状态从 WAITING 更新为 ACTIVE，
     * 同时写入接入座席 ID（agent_id 字段）。
     * 幂等：重复消费时 DB 行已是 ACTIVE，UPDATE 影响行数为 0，静默忽略。
     */
    private void handleSessionAccept(Map<String, Object> payload, String sessionId) {
        persistRepository.activateConversation(
            sessionId,
            str(payload, ConversationStreamEvent.FIELD_AGENT_ID),
            toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP)));
    }

    /**
     * 处理 SESSION_TRANSFER：更新 DB 中的 agent_id 为目标座席。
     * 状态保持 ACTIVE，仅所有权变更。
     */
    private void handleSessionTransfer(Map<String, Object> payload, String sessionId) {
        persistRepository.transferConversation(
            sessionId,
            str(payload, ConversationStreamEvent.FIELD_TO_AGENT_ID));
    }

    /**
     * 处理 SESSION_END：更新会话状态为 CLOSED，记录结束时间。
     */
    private void handleSessionEnd(Map<String, Object> payload, String sessionId) {
        persistRepository.closeConversation(
            sessionId,
            toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP)));
    }

    /**
     * 处理 MESSAGE：写入消息明细，使用 {@link MessageRole} 枚举映射角色字段。
     * I1 修复：fromValue() 返回 null 时（未知/缺失 role）直接 ACK 丢弃，
     * 避免 null 写入 NOT NULL 列触发 DB 异常 → 无限重试 → DLQ 堆积。
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
        entity.setContent(str(payload, ConversationStreamEvent.FIELD_CONTENT));
        // seq 由 ConversationHistoryRepository.nextSeq 生成，缺失（旧版本兼容）则置 null
        Object rawSeq = payload.get(ConversationStreamEvent.FIELD_SEQ);
        if (rawSeq != null) {
            try {
                entity.setSeq(Long.parseLong(rawSeq.toString()));
            } catch (NumberFormatException e) {
                log.warn("[MQ Consumer] 非法 seq={} sessionId={}", rawSeq, sessionId);
            }
        }
        entity.setCreatedAt(toOffsetDateTime(longVal(payload, ConversationStreamEvent.FIELD_TIMESTAMP)));
        persistRepository.saveMessages(List.of(entity));
    }

    // -------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------

    /** 安全获取 Map 中的字符串值，键不存在时返回 null */
    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    /** 安全获取 Map 中的 long 值，键不存在或格式非法时返回当前时间戳 */
    private long longVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return Instant.now().getEpochSecond();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return Instant.now().getEpochSecond();
        }
    }

    /** epoch seconds → OffsetDateTime（UTC） */
    private OffsetDateTime toOffsetDateTime(long epochSeconds) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }
}
