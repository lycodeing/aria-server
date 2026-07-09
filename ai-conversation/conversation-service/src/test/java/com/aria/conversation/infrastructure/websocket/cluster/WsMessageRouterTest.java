package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.conversation.infrastructure.websocket.AgentConnectionRegistry;
import com.aria.conversation.infrastructure.websocket.VisitorNotifier;
import com.aria.conversation.infrastructure.websocket.message.WsChatMessage;
import com.aria.conversation.infrastructure.websocket.message.WsMessageType;
import com.aria.conversation.infrastructure.websocket.cluster.WsClusterConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WsMessageRouter")
class WsMessageRouterTest {

    @Mock PodIdentity podIdentity;
    @Mock WsPresenceRegistry presenceRegistry;
    @Mock AgentConnectionRegistry agentRegistry;
    @Mock VisitorNotifier visitorNotifier;
    @Mock RabbitTemplate rabbitTemplate;

    private WsMessageRouter router;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        router = new WsMessageRouter(podIdentity, presenceRegistry, agentRegistry,
                visitorNotifier, rabbitTemplate, objectMapper);
    }

    private WsChatMessage chatMsg() {
        return WsChatMessage.fromVisitor("sess-1", "hello", 1L, 1000L);
    }

    @Test
    @DisplayName("座席在本 Pod：直接 broadcast")
    void sendToAgent_local_broadcasts_directly() {
        when(presenceRegistry.getAgentPods("agent-1")).thenReturn(Set.of("pod-A"));
        when(podIdentity.isLocal("pod-A")).thenReturn(true);
        router.sendToAgent("agent-1", chatMsg());
        verify(agentRegistry).broadcast("agent-1", chatMsg());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("座席在远端 Pod：发 MQ")
    void sendToAgent_remote_sends_mq() {
        when(presenceRegistry.getAgentPods("agent-1")).thenReturn(Set.of("pod-B"));
        when(podIdentity.isLocal("pod-B")).thenReturn(false);
        router.sendToAgent("agent-1", chatMsg());
        verify(rabbitTemplate).convertAndSend(eq(WsClusterConstants.WS_DELIVERY_EXCHANGE), eq("pod-B"), any(WsDeliveryCommand.class));
        verifyNoInteractions(agentRegistry);
    }

    @Test
    @DisplayName("访客在本 Pod：直接 notifyVisitor")
    void sendToVisitor_local_notifies_directly() {
        when(presenceRegistry.getVisitorPod("sess-1")).thenReturn("pod-A");
        when(podIdentity.isLocal("pod-A")).thenReturn(true);
        router.sendToVisitor("sess-1", chatMsg());
        verify(visitorNotifier).notifyVisitor("sess-1", chatMsg());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("访客在远端 Pod：发 MQ")
    void sendToVisitor_remote_sends_mq() {
        when(presenceRegistry.getVisitorPod("sess-1")).thenReturn("pod-B");
        when(podIdentity.isLocal("pod-B")).thenReturn(false);
        router.sendToVisitor("sess-1", chatMsg());
        verify(rabbitTemplate).convertAndSend(eq(WsClusterConstants.WS_DELIVERY_EXCHANGE), eq("pod-B"), any(WsDeliveryCommand.class));
    }

    @Test
    @DisplayName("座席不在线：不调用任何推送")
    void sendToAgent_offline_skips() {
        when(presenceRegistry.getAgentPods("agent-1")).thenReturn(Set.of());
        router.sendToAgent("agent-1", chatMsg());
        verifyNoInteractions(agentRegistry, rabbitTemplate);
    }

    @Test
    @DisplayName("访客不在线：不调用任何推送")
    void sendToVisitor_offline_skips() {
        when(presenceRegistry.getVisitorPod("sess-1")).thenReturn(null);
        router.sendToVisitor("sess-1", chatMsg());
        verifyNoInteractions(visitorNotifier, rabbitTemplate);
    }

    @Test
    @DisplayName("sendKick 发 KICK_AGENT 命令到目标 Pod")
    void sendKick_sends_kick_command() {
        router.sendKick("pod-B", "agent-1", "ws-new-123");
        ArgumentCaptor<WsDeliveryCommand> captor = ArgumentCaptor.forClass(WsDeliveryCommand.class);
        verify(rabbitTemplate).convertAndSend(eq("ws.delivery"), eq("pod-B"), captor.capture());
        WsDeliveryCommand cmd = captor.getValue();
        assertThat(cmd.targetType()).isEqualTo(WsDeliveryCommand.TargetType.KICK_AGENT);
        assertThat(cmd.targetId()).isEqualTo("agent-1");
        assertThat(cmd.excludeWsSessionId()).isEqualTo("ws-new-123");
    }
}
