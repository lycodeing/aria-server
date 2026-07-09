package com.aria.conversation.infrastructure.mq;

import com.aria.common.core.util.JsonUtils;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.MessageRole;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * 发布单条对话消息（MESSAGE 类型），承载完整 tool 上下文。
     *
     * <p>与四参数重载相比，本方法支持 {@link ConversationMessage#toolRequestId()}、
     * {@link ConversationMessage#toolName()}、{@link ConversationMessage#toolCalls()} 落库，
     * 让 DB 保存完整 LangChain4j tool_call ↔ tool_result 链路。
     *
     * <p>{@code Map.of} 不允许 null value，故改用 {@link LinkedHashMap} 组装，
     * 可选 tool 字段仅在非空时写入 payload。
     *
     * @param sessionId 会话 ID
     * @param dbRole    DB 侧使用的角色（assistant 消息在 DB 里可能标记为 agent 便于质检）
     * @param msg       消息领域对象
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishMessage(String sessionId, String dbRole, ConversationMessage msg) {
        Map<String, Object> payload = new LinkedHashMap<>(10);
        payload.put(ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.MESSAGE.name());
        payload.put(ConversationStreamEvent.FIELD_SESSION_ID, sessionId);
        payload.put(ConversationStreamEvent.FIELD_ROLE, dbRole);
        // content 可能为 null（tool_call-only assistant 消息），Consumer 端负责空串兜底
        payload.put(ConversationStreamEvent.FIELD_CONTENT, msg.content() != null ? msg.content() : "");
        payload.put(ConversationStreamEvent.FIELD_SEQ, msg.seq());
        payload.put(ConversationStreamEvent.FIELD_TIMESTAMP, Instant.now().getEpochSecond());

        if (msg.toolRequestId() != null) {
            payload.put(ConversationStreamEvent.FIELD_TOOL_REQUEST_ID, msg.toolRequestId());
        }
        if (msg.toolName() != null) {
            payload.put(ConversationStreamEvent.FIELD_TOOL_NAME, msg.toolName());
        }
        List<ConversationMessage.ToolCall> toolCalls = msg.toolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 序列化为 JSON 字符串，避免 RabbitMQ Jackson converter 对嵌套 record 的类型推断问题
            payload.put(ConversationStreamEvent.FIELD_TOOL_CALLS, JsonUtils.toJsonString(toolCalls));
        }

        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.debug("[MQ] MESSAGE published sessionId={} role={} seq={} hasToolCalls={}",
                sessionId, dbRole, msg.seq(), toolCalls != null && !toolCalls.isEmpty());
    }

    /**
     * 发布会话开始事件（SESSION_START 类型），由 SessionQueueService.enqueue() 触发。
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public void publishSessionStart(String sessionId, String visitorName,
                                    String transferReason, String tag, long timestamp) {
        Map<String, Object> payload = Map.of(
                ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.SESSION_START.name(),
                ConversationStreamEvent.FIELD_SESSION_ID, sessionId,
                ConversationStreamEvent.FIELD_VISITOR_NAME, visitorName != null ? visitorName : "访客",
                ConversationStreamEvent.FIELD_TRANSFER_REASON, transferReason != null ? transferReason : "",
                ConversationStreamEvent.FIELD_TAG, tag != null && !tag.isBlank() ? tag : "咨询",
                ConversationStreamEvent.FIELD_TIMESTAMP, timestamp
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
        Map<String, Object> payload = Map.of(
                ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.SESSION_ACCEPT.name(),
                ConversationStreamEvent.FIELD_SESSION_ID, sessionId,
                ConversationStreamEvent.FIELD_AGENT_ID, agentId,
                ConversationStreamEvent.FIELD_TIMESTAMP, timestamp
        );
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
        Map<String, Object> payload = Map.of(
                ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.SESSION_TRANSFER.name(),
                ConversationStreamEvent.FIELD_SESSION_ID, sessionId,
                ConversationStreamEvent.FIELD_FROM_AGENT_ID, fromAgentId,
                ConversationStreamEvent.FIELD_TO_AGENT_ID, toAgentId,
                ConversationStreamEvent.FIELD_TIMESTAMP, timestamp
        );
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
                ConversationStreamEvent.FIELD_TYPE, ConversationStreamEvent.Type.SESSION_END.name(),
                ConversationStreamEvent.FIELD_SESSION_ID, sessionId,
                ConversationStreamEvent.FIELD_TIMESTAMP, Instant.now().getEpochSecond()
        );
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
        log.info("[MQ] SESSION_END published sessionId={}", sessionId);
    }
}
