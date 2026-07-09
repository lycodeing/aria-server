package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.conversation.infrastructure.websocket.AgentConnectionRegistry;
import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
import com.aria.conversation.infrastructure.websocket.message.WsChatMessage;
import com.aria.conversation.infrastructure.websocket.message.WsConnectedMessage;
import com.aria.conversation.infrastructure.websocket.message.WsErrorMessage;
import com.aria.conversation.infrastructure.websocket.message.WsKickedOutMessage;
import com.aria.conversation.infrastructure.websocket.message.WsTypingMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * WS 跨 Pod 投递消费者。
 *
 * <p>监听本 Pod 专属的匿名队列（exclusive + autoDelete），routing key = podId（AnonymousQueue 名称）。
 * {@link WsMessageRouter} 根据 Redis presence 精确路由投递命令到目标 Pod 队列。
 *
 * <p>队列声明说明：{@code name = "#{@podIdentity.get()}"} 使 @RabbitListener 监听
 * {@link PodIdentity} 已声明的同一个队列，不额外创建新队列。
 * Exchange 由 @Exchange 注解自动声明（durable=true），无需在 RabbitMQConfig 中额外 @Bean。
 *
 * <p>payload 还原：按 {@link com.aria.conversation.infrastructure.websocket.message.WsMessageType}
 * 枚举 if-instanceof 链反序列化，避免 Jackson 类型擦除问题（不使用 Class.forName）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsDeliveryConsumer {

    private final AgentConnectionRegistry agentRegistry;
    private final VisitorNotifier         visitorNotifier;
    private final ObjectMapper            objectMapper;

    /**
     * 接收跨 Pod 投递的 WS 消息，执行本地推送。
     * routing key = #{@podIdentity.get()}（SpEL，运行时求值为本 Pod AnonymousQueue 名称）。
     */
    @RabbitListener(bindings = @QueueBinding(
            value    = @Queue(name = "#{@podIdentity.get()}", exclusive = "true", autoDelete = "true"),
            exchange = @Exchange(value = "ws.delivery", type = "direct", durable = "true"),
            key      = "#{@podIdentity.get()}"
    ))
    public void onDelivery(WsDeliveryCommand cmd) {
        try {
            switch (cmd.targetType()) {
                case AGENT -> {
                    Object payload = restorePayload(cmd);
                    agentRegistry.broadcast(cmd.targetId(), payload);
                }
                case VISITOR -> {
                    Object payload = restorePayload(cmd);
                    visitorNotifier.notifyVisitor(cmd.targetId(), payload);
                }
                case KICK_AGENT -> {
                    // 远端 Pod 上该 agentId 的所有连接均为旧连接，全部推 KICKED_OUT 后关闭
                    log.info("[WsDelivery] 收到 KICK 命令 agentId={} srcWsId={}",
                            cmd.targetId(), cmd.excludeWsSessionId());
                    agentRegistry.broadcast(cmd.targetId(), WsKickedOutMessage.INSTANCE);
                    agentRegistry.closeAll(cmd.targetId());
                }
                default -> log.warn("[WsDelivery] 未知 targetType={}", cmd.targetType());
            }
        } catch (Exception e) {
            log.error("[WsDelivery] 消息处理异常 type={} id={}",
                    cmd.targetType(), cmd.targetId(), e);
        }
        log.debug("[WsDelivery] 本地推送完成 type={} id={}", cmd.targetType(), cmd.targetId());
    }

    /**
     * 按 WsMessageType 枚举还原 payload。
     * Java 17 if-else if 链（非 Java 21 pattern matching switch）。
     * KICKED_OUT 不走此路径，由 KICK_AGENT targetType 直接处理。
     */
    private Object restorePayload(WsDeliveryCommand cmd) throws JsonProcessingException {
        if (cmd.wsMessageType() == com.aria.conversation.infrastructure.websocket.message.WsMessageType.MESSAGE) {
            return objectMapper.readValue(cmd.payloadJson(), WsChatMessage.class);
        } else if (cmd.wsMessageType() == com.aria.conversation.infrastructure.websocket.message.WsMessageType.TYPING) {
            return objectMapper.readValue(cmd.payloadJson(), WsTypingMessage.class);
        } else if (cmd.wsMessageType() == com.aria.conversation.infrastructure.websocket.message.WsMessageType.CONNECTED) {
            return objectMapper.readValue(cmd.payloadJson(), WsConnectedMessage.class);
        } else if (cmd.wsMessageType() == com.aria.conversation.infrastructure.websocket.message.WsMessageType.ERROR) {
            return objectMapper.readValue(cmd.payloadJson(), WsErrorMessage.class);
        } else {
            throw new IllegalArgumentException(
                    "不支持跨 Pod 投递的消息类型: " + cmd.wsMessageType());
        }
    }
}
