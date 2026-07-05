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
    boolean supports(RouteResult route);
    Flux<ChatEvent> handle(String sessionId, String userMessage,
                           RouteResult route, Map<String, Object> sessionCtx);
}
