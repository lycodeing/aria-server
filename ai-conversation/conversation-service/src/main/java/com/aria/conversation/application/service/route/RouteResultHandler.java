package com.aria.conversation.application.service.route;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline.RouteResult;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * DIT Pipeline 路由结果处理策略。
 * 新增 RouteResult 子类型只需新增 @Component 实现（开闭原则）。
 */
public interface RouteResultHandler {

    /**
     * 判断本处理器是否支持给定的路由结果类型。
     *
     * @param route DIT pipeline 路由结果
     * @return true 表示本处理器可以处理该结果
     */
    boolean supports(RouteResult route);

    /**
     * 处理路由结果，返回 ChatEvent 流。
     *
     * @param sessionId  会话 ID
     * @param userMessage 用户原始消息
     * @param route      路由结果
     * @param sessionCtx 会话上下文（键值对，可携带额外元数据）
     * @return ChatEvent 响应流
     */
    Flux<ChatEvent> handle(String sessionId, String userMessage,
                           RouteResult route, Map<String, Object> sessionCtx);
}
