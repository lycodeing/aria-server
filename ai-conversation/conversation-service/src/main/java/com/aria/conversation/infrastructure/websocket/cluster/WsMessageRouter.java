package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.conversation.infrastructure.websocket.AgentConnectionRegistry;
import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * WS 跨 Pod 消息路由器。
 *
 * <p>根据 {@link WsPresenceRegistry} 中的 presence 信息决定本地直推还是通过
 * {@code ws.delivery} RabbitMQ Direct Exchange 跨 Pod 投递。
 *
 * <p>本地路径直接注入 {@link VisitorNotifier}（由 {@code VisitorSessionRegistry} 实现），
 * 不再通过 ApplicationContext 运行时查找。VisitorSessionRegistry 独立于
 * ChatWebSocketHandler，因此本类与 ChatWebSocketHandler 之间不再存在循环依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsMessageRouter {

    private final PodIdentity             podIdentity;
    private final WsPresenceRegistry      presenceRegistry;
    private final AgentConnectionRegistry agentRegistry;
    private final VisitorNotifier         visitorNotifier;
    private final RabbitTemplate          rabbitTemplate;
    private final ObjectMapper            objectMapper;

    /**
     * 向座席推送消息。本 Pod 直接 broadcast；跨 Pod 发 MQ。
     * BROADCAST 模式下 pods 可包含多个元素（座席多端跨 Pod），逐一处理。
     */
    public void sendToAgent(String agentId, Object payload) {
        Set<String> pods = presenceRegistry.getAgentPods(agentId);
        if (pods.isEmpty()) {
            log.warn("[WsRouter] 座席不在线 agentId={}", agentId);
            return;
        }
        for (String pod : pods) {
            if (podIdentity.isLocal(pod)) {
                agentRegistry.broadcast(agentId, payload);
            } else {
                deliver(pod, WsDeliveryCommand.toAgent(agentId, payload, objectMapper));
            }
        }
    }

    /**
     * 向访客推送消息。本 Pod 直接推；跨 Pod 发 MQ。
     *
     * <p>本地路径直接调用 {@code visitorNotifier.notifyVisitor()}（即 {@link VisitorSessionRegistry} 的本地推送），
     * 不是 router.sendToVisitor()，防止无限递归。
     * 循环依赖已通过提取 {@link VisitorSessionRegistry} 从根源消除，无需 ApplicationContext 运行时查找。
     */
    public void sendToVisitor(String sessionId, Object payload) {
        String pod = presenceRegistry.getVisitorPod(sessionId);
        if (pod == null) {
            log.warn("[WsRouter] 访客不在线 sessionId={}", sessionId);
            return;
        }
        if (podIdentity.isLocal(pod)) {
            visitorNotifier.notifyVisitor(sessionId, payload);
        } else {
            deliver(pod, WsDeliveryCommand.toVisitor(sessionId, payload, objectMapper));
        }
    }

    /**
     * 向目标 Pod 发送 KICK 命令，通知其关闭该 agentId 的所有旧连接。
     *
     * @param targetPod      目标 Pod 的 podId
     * @param agentId        被踢座席 ID
     * @param newWsSessionId 新连接的 wsSessionId，仅供目标 Pod 日志追踪
     */
    public void sendKick(String targetPod, String agentId, String newWsSessionId) {
        deliver(targetPod, WsDeliveryCommand.kickAgent(agentId, newWsSessionId));
    }

    private void deliver(String targetPod, WsDeliveryCommand cmd) {
        try {
            rabbitTemplate.convertAndSend(WsClusterConstants.WS_DELIVERY_EXCHANGE, targetPod, cmd);
            log.debug("[WsRouter] 跨 Pod 投递 targetPod={} type={} id={}",
                    targetPod, cmd.targetType(), cmd.targetId());
        } catch (Exception e) {
            log.warn("[WsRouter] MQ 投递失败 targetPod={} msg={}", targetPod, e.getMessage());
        }
    }
}
