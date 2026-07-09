package com.aria.conversation.infrastructure.websocket;

/**
 * 访客消息推送接口。
 *
 * <p>解耦 {@link AgentChannelWsHandler} 对 {@link ChatWebSocketHandler} 的直接依赖，
 * 便于单元测试 Mock 和后续替换实现。
 */
public interface VisitorNotifier {

    /**
     * 向指定会话的访客推送消息。
     *
     * @param sessionId 会话 ID
     * @param payload   消息对象（将被序列化为 JSON）
     */
    void notifyVisitor(String sessionId, Object payload);

    /**
     * 以正常状态（code=1000 NORMAL）关闭访客端 WebSocket 连接。
     * 用于座席主动结束会话时通知访客。
     *
     * @param sessionId 会话 ID
     */
    void closeVisitorSessionNormal(String sessionId);
}
