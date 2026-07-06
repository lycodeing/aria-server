package com.aria.conversation.application.service.route;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.application.service.SystemPromptBuilder;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.knowledge.KnowledgeClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 处理 {@link RouteResult.FallbackResult}：执行 RAG 检索并通过 LLM 流式生成回复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FallbackRouteHandler implements RouteResultHandler {

    private static final String ROLE_ASSISTANT = "assistant";

    private final DynamicModelFactory modelFactory;
    private final KnowledgeClient knowledgeClient;
    private final ConversationHistoryRepository historyRepository;

    @Override
    public boolean supports(RouteResult route) {
        return route instanceof RouteResult.FallbackResult;
    }

    @Override
    public Flux<ChatEvent> handle(String sessionId, String userMessage,
                                  RouteResult route, Map<String, Object> sessionCtx) {
        RouteResult.FallbackResult r = (RouteResult.FallbackResult) route;

        List<KnowledgeSearchResult.Hit> hits = knowledgeClient.search(userMessage);
        String systemPrompt = SystemPromptBuilder.build(hits, r.systemPromptAddon(), null);
        List<ChatMessage> aiPrompt = toAiPrompt(historyRepository.findAll(sessionId));

        StringBuilder reply = new StringBuilder();
        return modelFactory.streamChat(aiPrompt, systemPrompt)
                .map(content -> {
                    reply.append(content);
                    return ChatEvent.data(content);
                })
                .doOnError(e -> log.warn("[AI] 降级对话失败 sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just(ChatEvent.data("抱歉，AI 服务暂时不可用，请稍后重试。")))
                .doFinally(signal -> {
                    if (!reply.isEmpty()) {
                        historyRepository.append(sessionId, ROLE_ASSISTANT, reply.toString());
                    }
                });
    }

    private List<ChatMessage> toAiPrompt(List<ConversationMessage> history) {
        return history.stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
    }
}
