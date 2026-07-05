package com.aria.conversation.application.service.route;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.application.service.payload.ToolCallPayload;
import com.aria.conversation.application.service.payload.ToolDonePayload;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline.RouteResult;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.aria.conversation.infrastructure.dit.pipeline.ToolExecutor;
import com.aria.conversation.infrastructure.knowledge.KnowledgeClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExecuteRouteHandler implements RouteResultHandler {

    private static final String ROLE_ASSISTANT = "assistant";

    private final ToolExecutor toolExecutor;
    private final DynamicModelFactory aiClient;
    private final KnowledgeClient knowledgeClient;
    private final ConversationHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(RouteResult route) { return route instanceof RouteResult.ExecuteResult; }

    @Override
    public Flux<ChatEvent> handle(String sessionId, String userMessage,
                                   RouteResult route, Map<String, Object> sessionCtx) {
        RouteResult.ExecuteResult r = (RouteResult.ExecuteResult) route;
        // Tool execution and RAG are blocking; outer subscribeOn(boundedElastic) already covers us
        return Mono.fromCallable(() -> {
                    List<ToolCallResult> toolResults = toolExecutor.executeRequired(
                            r.intentConfig(), r.resolvedSlots(), sessionCtx);
                    String toolContext = buildToolContext(toolResults);
                    List<KnowledgeSearchResult.Hit> hits = r.intentConfig().skipRag()
                            ? List.of() : knowledgeClient.search(userMessage);
                    String systemPrompt = buildSystemPromptWithToolContext(
                            hits, r.systemPromptAddon(), toolContext);
                    return new PrepareData(toolResults, systemPrompt);
                })
                .flatMapMany(prepared -> {
                    List<ToolCallResult> toolResults = prepared.toolResults();
                    String systemPrompt = prepared.systemPrompt();

                    List<ChatEvent> toolEvents = new ArrayList<>();
                    for (ToolCallResult tr : toolResults) {
                        try {
                            toolEvents.add(ChatEvent.toolCall(objectMapper.writeValueAsString(
                                    ToolCallPayload.running(tr.getToolCode()))));
                            toolEvents.add(ChatEvent.toolDone(objectMapper.writeValueAsString(
                                    ToolDonePayload.from(tr))));
                        } catch (Exception e) {
                            log.warn("[DIT] 工具事件序列化失败 tool={}", tr.getToolCode(), e);
                        }
                    }

                    List<ChatMessage> aiPrompt = toAiPrompt(historyRepository.findAll(sessionId));
                    StringBuilder assistantReply = new StringBuilder();
                    Flux<ChatEvent> aiStream = aiClient.streamChat(aiPrompt, systemPrompt)
                            .map(content -> { assistantReply.append(content); return ChatEvent.data(content); })
                            .doOnError(e -> log.warn("[AI] 流式对话失败 sessionId={}", sessionId, e))
                            .onErrorResume(e -> Flux.just(ChatEvent.data("抱歉，AI 服务暂时不可用，请稍后重试。")))
                            .doFinally(signal -> {
                                if (!assistantReply.isEmpty()) {
                                    historyRepository.append(sessionId, ROLE_ASSISTANT, assistantReply.toString());
                                }
                            });

                    return toolEvents.isEmpty()
                            ? aiStream
                            : Flux.concat(Flux.fromIterable(toolEvents), aiStream);
                });
    }

    private String buildToolContext(List<ToolCallResult> results) {
        if (results == null || results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ToolCallResult r : results) {
            if (r.isSuccess() && r.getResponse() != null && !r.getResponse().isBlank()) {
                sb.append("【").append(r.getToolCode()).append("查询结果】\n")
                  .append(r.getResponse()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private String buildSystemPromptWithToolContext(List<KnowledgeSearchResult.Hit> hits,
                                                     String systemPromptAddon,
                                                     String toolContext) {
        StringBuilder sb = new StringBuilder();
        if (toolContext != null && !toolContext.isBlank()) {
            sb.append("【实时查询数据】（请优先依据以下数据回答）\n\n")
              .append(toolContext).append("\n---\n");
        }
        if (hits != null && !hits.isEmpty()) {
            sb.append("【参考资料】（请优先依据以下内容回答，无需在回答中标注来源编号）\n\n");
            for (int i = 0; i < hits.size(); i++) {
                KnowledgeSearchResult.Hit hit = hits.get(i);
                String label = (hit.getBreadcrumb() != null && !hit.getBreadcrumb().isBlank())
                        ? hit.getBreadcrumb() : "文档片段";
                sb.append("[").append(i + 1).append("] ").append(label).append("\n")
                  .append(hit.getContent() != null ? hit.getContent() : "").append("\n\n");
            }
            sb.append("---\n");
        }
        if (systemPromptAddon != null && !systemPromptAddon.isBlank()) {
            sb.append(systemPromptAddon).append("\n");
        }
        // Keep same base prompt as original ChatAppService
        sb.append("""
                你是一名专业的智能客服助手。
                请用简洁、友好的语言回答用户问题。
                如果涉及订单、退款等敏感操作，引导用户验证身份。
                回答要简明扼要，免冗长说明。
                """);
        return sb.toString();
    }

    private List<ChatMessage> toAiPrompt(List<ConversationMessage> history) {
        return history.stream().map(m -> new ChatMessage(m.role(), m.content())).toList();
    }

    private record PrepareData(List<ToolCallResult> toolResults, String systemPrompt) {}
}
