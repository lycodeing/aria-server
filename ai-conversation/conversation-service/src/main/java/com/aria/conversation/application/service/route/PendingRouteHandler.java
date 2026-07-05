package com.aria.conversation.application.service.route;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline.RouteResult;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingRouteHandler implements RouteResultHandler {

    private static final String ROLE_ASSISTANT = "assistant";

    private final ConversationHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(RouteResult route) { return route instanceof RouteResult.PendingResult; }

    @Override
    public Flux<ChatEvent> handle(String sessionId, String userMessage,
                                   RouteResult route, Map<String, Object> sessionCtx) {
        RouteResult.PendingResult r = (RouteResult.PendingResult) route;
        historyRepository.append(sessionId, ROLE_ASSISTANT, r.promptMessage());
        if (r.candidates() != null && !r.candidates().isEmpty()) {
            try {
                String json = objectMapper.writeValueAsString(r.candidates());
                return Flux.just(ChatEvent.candidates(json));
            } catch (Exception e) {
                log.warn("[DIT] candidates 序列化失败 sessionId={}", sessionId, e);
            }
        }
        return Flux.just(ChatEvent.slotAsk(r.promptMessage()));
    }
}
