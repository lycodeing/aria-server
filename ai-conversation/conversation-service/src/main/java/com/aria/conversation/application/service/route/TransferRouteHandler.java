package com.aria.conversation.application.service.route;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.application.service.SessionQueueService;
import com.aria.conversation.application.service.payload.TransferPayload;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 处理 {@link RouteResult.TransferResult}：调用 SessionQueueService 入队，
 * 追加助手历史，返回 {@link ChatEvent#transfer(String)} 事件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferRouteHandler implements RouteResultHandler {

    private static final String TRANSFER_AUTO_REASON = "系统识别到用户需要人工服务";
    private static final String TRANSFER_DEFAULT_TAG = "咨询";
    private static final String ROLE_ASSISTANT       = "assistant";

    private final SessionQueueService sessionQueueService;
    private final ConversationHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(RouteResult route) {
        return route instanceof RouteResult.TransferResult;
    }

    @Override
    public Flux<ChatEvent> handle(String sessionId, String userMessage,
                                  RouteResult route, Map<String, Object> sessionCtx) {
        RouteResult.TransferResult r = (RouteResult.TransferResult) route;

        try {
            sessionQueueService.enqueue(sessionId, "访客", TRANSFER_AUTO_REASON, TRANSFER_DEFAULT_TAG);
        } catch (Exception e) {
            log.warn("[DIT] 自动转人工失败 sessionId={}", sessionId, e);
        }

        historyRepository.append(sessionId, ROLE_ASSISTANT, r.replyMessage());

        try {
            String json = objectMapper.writeValueAsString(
                    new TransferPayload(r.reason(), r.replyMessage()));
            return Flux.just(ChatEvent.transfer(json));
        } catch (JsonProcessingException e) {
            log.warn("[DIT] transfer payload 序列化失败 sessionId={}", sessionId, e);
            return Flux.just(ChatEvent.data(r.replyMessage()));
        }
    }
}
