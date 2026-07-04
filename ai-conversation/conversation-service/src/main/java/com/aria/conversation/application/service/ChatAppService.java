package com.aria.conversation.application.service;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicAiClient;
import com.aria.conversation.infrastructure.ai.IntentClassifier;
import com.aria.conversation.infrastructure.ai.IntentResult;
import com.aria.conversation.infrastructure.ai.IntentType;
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline;
import com.aria.conversation.infrastructure.dit.pipeline.DitPipeline.RouteResult;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.aria.conversation.infrastructure.dit.pipeline.ToolExecutor;
import com.aria.conversation.infrastructure.knowledge.KnowledgeClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 对话应用服务。
 * 编排多轮对话历史维护（委托给 ConversationHistoryRepository）、
 * 知识库检索（RAG）和 AI 回复生成。
 */
@Slf4j
@Service
public class ChatAppService {

    private static final String ROLE_USER      = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private static final String AGENT_HINT_MSG = "（消息已发送给人工客服）";
    private static final String OUT_OF_SCOPE_REPLY =
            "抱歉，我是专业的客服助手，只能回答业务相关的问题，无法帮您解答这个问题。";
    private static final String TRANSFER_AUTO_REASON = "系统识别到用户需要人工服务";
    private static final String TRANSFER_DEFAULT_TAG = "咨询";

    private static final String BASE_SYSTEM_PROMPT = """
            你是一名专业的智能客服助手。
            请用简洁、友好的语言回答用户问题。
            如果涉及订单、退款等敏感操作，引导用户验证身份。
            回答要简明扼要，避免冗长说明。
            """;

    private final DynamicAiClient aiClient;
    private final ConversationHistoryRepository historyRepository;
    private final KnowledgeClient knowledgeClient;
    private final IntentClassifier intentClassifier;
    private final SessionQueueService sessionQueueService;
    private final DitPipeline ditPipeline;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    public ChatAppService(DynamicAiClient aiClient,
                          ConversationHistoryRepository historyRepository,
                          KnowledgeClient knowledgeClient,
                          IntentClassifier intentClassifier,
                          SessionQueueService sessionQueueService,
                          DitPipeline ditPipeline,
                          ToolExecutor toolExecutor,
                          ObjectMapper objectMapper) {
        this.aiClient            = aiClient;
        this.historyRepository   = historyRepository;
        this.knowledgeClient     = knowledgeClient;
        this.intentClassifier    = intentClassifier;
        this.sessionQueueService = sessionQueueService;
        this.ditPipeline         = ditPipeline;
        this.toolExecutor        = toolExecutor;
        this.objectMapper        = objectMapper;
    }

    // -------------------------------------------------------
    // 统一对话入口（DDD：Controller 只调这一个方法）
    // -------------------------------------------------------

    /**
     * 统一流式对话入口，返回 {@link ChatEvent} 流供 Controller 转换为 SSE。
     *
     * <p>所有路由决策（人工接入判断、DIT Pipeline、通用 FAQ）全部在此方法内完成，
     * Controller 只负责格式转换，不含任何业务判断。
     *
     * @param sessionId  会话 ID
     * @param message    用户消息
     * @param domainCode 领域标识（可选，null 走通用流程）
     */
    public Flux<ChatEvent> stream(String sessionId, String message, String domainCode) {
        // 已接入人工 → 存历史，返回提示，不走 AI
        if (sessionQueueService.isActive(sessionId)) {
            historyRepository.append(sessionId, ROLE_USER, message);
            return Flux.just(ChatEvent.data(AGENT_HINT_MSG));
        }

        // 有 domainCode → DIT Pipeline（直接返回 Flux<ChatEvent>，含语义事件）
        if (StringUtils.isNotBlank(domainCode)) {
            return streamChatWithDomain(sessionId, message, domainCode, java.util.Map.of());
        }

        // 通用 FAQ 流程：RAG + 意图路由 + LLM，含 sources event
        return streamFaq(sessionId, message);
    }

    /**
     * 用户主动请求转人工（供 Controller 调用，消除 Controller 对 SessionQueueService 的直接依赖）。
     *
     * @param sessionId      会话 ID
     * @param userName       用户名称
     * @param transferReason 转人工原因
     * @param tag            标签
     * @return 队列项
     */
    public SessionQueueItem requestTransfer(String sessionId, String userName,
                                             String transferReason, String tag) {
        return sessionQueueService.enqueue(sessionId, userName, transferReason, tag);
    }

    // -------------------------------------------------------
    // 对话主流程
    // -------------------------------------------------------

    /**
     * 检索与用户问题相关的知识块（RAG 第一步）。
     * 运行于 Spring MVC 阻塞线程，直接调用，不使用响应式包装。
     */
    public List<KnowledgeSearchResult.Hit> searchHits(String userMessage) {
        return knowledgeClient.search(userMessage);
    }

    /**
     * 通用 FAQ 流程：RAG + 意图路由 + LLM，包含 sources event。
     * 返回 {@link ChatEvent} 流，有知识库命中时先发 sources 再发 AI token。
     */
    private Flux<ChatEvent> streamFaq(String sessionId, String message) {
        List<KnowledgeSearchResult.Hit> hits = searchHits(message);
        Flux<ChatEvent> aiEvents = streamChat(sessionId, message, hits).map(ChatEvent::data);

        if (hits.isEmpty()) {
            return aiEvents;
        }
        return Flux.concat(
                Flux.just(ChatEvent.sources(buildSourcesJson(hits))),
                aiEvents
        );
    }

    /**
     * 将 hits 序列化为 JSON 数组，格式：[{"docId":"...","label":"..."}]。
     * 序列化失败时返回空数组，不阻断 SSE 流。
     */
    public String buildSourcesJson(List<KnowledgeSearchResult.Hit> hits) {
        if (hits.isEmpty()) return "[]";
        List<java.util.Map<String, String>> sources = hits.stream()
                .map(h -> {
                    String label = (h.getBreadcrumb() != null && !h.getBreadcrumb().isBlank())
                            ? h.getBreadcrumb() : "文档片段";
                    return java.util.Map.of(
                            "docId", h.getDocId() != null ? h.getDocId() : "",
                            "label", label
                    );
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 流式对话（带预检索 hits），含意图路由。
     *
     * <p>路由逻辑：
     * <ol>
     *   <li>意图分类（LLM 轻量分类请求）</li>
     *   <li>TRANSFER_REQUEST / COMPLAINT → 自动入队转人工，返回提示流</li>
     *   <li>OUT_OF_SCOPE → 返回拒答模板流，不调 LLM</li>
     *   <li>CHITCHAT → 跳过 RAG，直接 LLM 回复</li>
     *   <li>FAQ_QUERY / UNKNOWN → 正常 RAG + LLM 流程</li>
     * </ol>
     */
    public Flux<String> streamChat(String sessionId, String userMessage,
                                   List<KnowledgeSearchResult.Hit> hits) {
        historyRepository.append(sessionId, ROLE_USER, userMessage);

        // 意图分类
        IntentResult intent = intentClassifier.classify(userMessage);
        log.debug("[Chat] sessionId={} intent={} confidence={}", sessionId, intent.intent(), intent.confidence());

        // 路由：需要转人工
        if (intent.requiresTransfer()) {
            try {
                sessionQueueService.enqueue(sessionId, "访客", TRANSFER_AUTO_REASON, TRANSFER_DEFAULT_TAG);
                log.info("[Chat] 自动转人工 sessionId={} intent={}", sessionId, intent.intent());
            } catch (Exception e) {
                log.warn("[Chat] 自动转人工失败 sessionId={}", sessionId, e);
            }
            String reply = intent.intent() == IntentType.COMPLAINT
                    ? "非常抱歉给您带来了不好的体验，我已为您转接人工客服，请稍候。"
                    : "好的，我已为您转接人工客服，请稍候。";
            historyRepository.append(sessionId, ROLE_ASSISTANT, reply);
            return Flux.just(reply);
        }

        // 路由：超出业务范围
        if (intent.intent() == IntentType.OUT_OF_SCOPE) {
            historyRepository.append(sessionId, ROLE_ASSISTANT, OUT_OF_SCOPE_REPLY);
            return Flux.just(OUT_OF_SCOPE_REPLY);
        }

        // 路由：闲聊 → 跳过 RAG
        List<KnowledgeSearchResult.Hit> effectiveHits = intent.skipRag() ? List.of() : hits;

        String systemPrompt = buildSystemPrompt(effectiveHits);
        List<ChatMessage> aiPrompt = toAiPrompt(historyRepository.findAll(sessionId));
        StringBuilder assistantReply = new StringBuilder();

        return aiClient.streamChat(aiPrompt, systemPrompt)
                .map(content -> {
                    assistantReply.append(content);
                    return content;
                })
                .doOnError(e -> log.warn("[AI] 流式对话失败 sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just("抱歉，AI 服务暂时不可用，请稍后重试。"))
                .doFinally(signal -> {
                    if (!assistantReply.isEmpty()) {
                        historyRepository.append(sessionId, ROLE_ASSISTANT, assistantReply.toString());
                    }
                });
    }

    /**
     * 流式对话（自动检索），内部调用 {@link #searchHits} 后委托带 hits 的重载。
     */
    public Flux<String> streamChat(String sessionId, String userMessage) {
        return streamChat(sessionId, userMessage, searchHits(userMessage));
    }

    /**
     * 领域感知流式对话（DIT Pipeline），返回 {@link ChatEvent} 流含语义事件类型。
     *
     * <p>整个 Route 阶段（DB 查询 + Redis + LLM 意图识别 + DISCOVER HTTP 调用）均为阻塞操作，
     * 通过 {@code subscribeOn(Schedulers.boundedElastic())} 整体切换到 IO 线程池，
     * 避免在 reactor-http-nio 线程上调用阻塞操作导致死锁。
     */
    public Flux<ChatEvent> streamChatWithDomain(String sessionId, String userMessage,
                                                 String domainCode,
                                                 java.util.Map<String, Object> sessionCtx) {
        return Mono.fromCallable(() -> {
                    // 以下均为阻塞操作，必须在 boundedElastic 线程上执行
                    historyRepository.append(sessionId, ROLE_USER, userMessage);
                    List<ChatMessage> recentHistory = toAiPrompt(historyRepository.findAll(sessionId));
                    return ditPipeline.route(sessionId, userMessage, domainCode,
                            recentHistory, sessionCtx);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(route -> buildDomainEventStream(sessionId, userMessage, route, sessionCtx));
    }

    /**
     * 非流式对话，返回完整回复文本。
     */
    public String chat(String sessionId, String userMessage) {
        historyRepository.append(sessionId, ROLE_USER, userMessage);
        List<KnowledgeSearchResult.Hit> hits = knowledgeClient.search(userMessage);
        String systemPrompt = buildSystemPrompt(hits);
        String reply = aiClient.chat(toAiPrompt(historyRepository.findAll(sessionId)), systemPrompt);
        historyRepository.append(sessionId, ROLE_ASSISTANT, reply);
        return reply;
    }

    // -------------------------------------------------------
    // 历史查询
    // -------------------------------------------------------

    /** 清除会话历史。 */
    public void clearHistory(String sessionId) {
        historyRepository.delete(sessionId);
    }

    /** 获取全量历史消息（最近 20 轮），用于前端加载上下文。 */
    public List<ConversationMessage> getHistory(String sessionId) {
        return historyRepository.findAll(sessionId);
    }

    /**
     * 增量获取历史消息（seq > sinceSeq），用于断线重连后补齐空窗。
     *
     * @param sinceSeq 客户端已知的最后一条消息 seq
     */
    public List<ConversationMessage> getHistorySince(String sessionId, long sinceSeq) {
        return historyRepository.findSince(sessionId, sinceSeq);
    }

    /** 保存访客消息（转人工后，HTTP SSE 路径不走 AI 时调用）。 */
    public void saveVisitorMessage(String sessionId, String content) {
        historyRepository.append(sessionId, ROLE_USER, content);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /**
     * DIT 路由结果处理，返回语义事件流 {@link ChatEvent}（Java 17 instanceof 链）。
     *
     * <p>PendingResult → 发 {@code slot_ask} 或 {@code candidates} 语义事件
     * <p>ExecuteResult → 发 {@code tool_call}/{@code tool_done} + AI token 流
     */
    private Flux<ChatEvent> buildDomainEventStream(String sessionId, String userMessage,
                                                    RouteResult route,
                                                    java.util.Map<String, Object> sessionCtx) {
        if (route instanceof RouteResult.TransferResult r) {
            try {
                sessionQueueService.enqueue(sessionId, "访客", TRANSFER_AUTO_REASON, TRANSFER_DEFAULT_TAG);
            } catch (Exception e) {
                log.warn("[DIT] 自动转人工失败 sessionId={}", sessionId, e);
            }
            historyRepository.append(sessionId, ROLE_ASSISTANT, r.replyMessage());
            return Flux.just(ChatEvent.data(r.replyMessage()));
        }
        if (route instanceof RouteResult.PendingResult r) {
            historyRepository.append(sessionId, ROLE_ASSISTANT, r.promptMessage());
            // 有候选项 → 只发 candidates 语义事件；无候选项 → 只发 slot_ask 语义事件
            // 前端从语义事件的 data 字段读取 promptMessage，不额外发 ChatEvent.data() 避免重复渲染
            if (r.candidates() != null && !r.candidates().isEmpty()) {
                try {
                    String candidatesJson = objectMapper.writeValueAsString(r.candidates());
                    return Flux.just(ChatEvent.candidates(candidatesJson));
                } catch (Exception e) {
                    log.warn("[DIT] candidates 序列化失败 sessionId={}", sessionId, e);
                }
            }
            return Flux.just(ChatEvent.slotAsk(r.promptMessage()));
        }
        if (route instanceof RouteResult.ExecuteResult r) {
            // 阻塞操作（工具调用 + RAG 检索）切换到 boundedElastic，避免在 reactor-http-nio 线程崩溃
            return Mono.fromCallable(() -> {
                        List<ToolCallResult> toolResults = toolExecutor.executeRequired(
                                r.intentConfig(), r.resolvedSlots(), sessionCtx);
                        String toolContext = buildToolContext(toolResults);
                        List<KnowledgeSearchResult.Hit> hits = r.intentConfig().skipRag()
                                ? List.of() : knowledgeClient.search(userMessage);
                        return new Object[]{toolResults, buildSystemPromptWithToolContext(
                                hits, r.systemPromptAddon(), toolContext)};
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(arr -> {
                        @SuppressWarnings("unchecked")
                        List<ToolCallResult> toolResults = (List<ToolCallResult>) arr[0];
                        String systemPrompt = (String) arr[1];

                        // 构造工具调用状态事件
                        List<ChatEvent> toolEvents = new java.util.ArrayList<>();
                        for (ToolCallResult tr : toolResults) {
                            try {
                                toolEvents.add(ChatEvent.toolCall(objectMapper.writeValueAsString(
                                        java.util.Map.of("tool", tr.getToolCode(), "status", "running"))));
                                toolEvents.add(ChatEvent.toolDone(objectMapper.writeValueAsString(
                                        java.util.Map.of("tool", tr.getToolCode(),
                                                "status", tr.getStatus(),
                                                "duration_ms", tr.getDurationMs()))));
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
        // FallbackResult 或未知类型 → 降级通用流程
        String addon = route instanceof RouteResult.FallbackResult fr ? fr.systemPromptAddon() : null;
        return buildAiStream(sessionId, userMessage, addon).map(ChatEvent::data);
    }

    /** 将工具调用结果格式化为注入 LLM Prompt 的 context 字符串。 */
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

    /** 构建含工具结果、知识库内容和领域说明的 system prompt。 */
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
        sb.append(BASE_SYSTEM_PROMPT);
        return sb.toString();
    }

    /** 构建 RAG + LLM 流式回答（带领域 system prompt addon）。 */
    private Flux<String> buildAiStream(String sessionId, String userMessage, String systemPromptAddon) {
        List<KnowledgeSearchResult.Hit> hits = knowledgeClient.search(userMessage);
        String systemPrompt = buildSystemPromptWithAddon(hits, systemPromptAddon);
        List<ChatMessage> aiPrompt = toAiPrompt(historyRepository.findAll(sessionId));
        StringBuilder assistantReply = new StringBuilder();
        return aiClient.streamChat(aiPrompt, systemPrompt)
                .map(content -> { assistantReply.append(content); return content; })
                .doOnError(e -> log.warn("[AI] 流式对话失败 sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just("抱歉，AI 服务暂时不可用，请稍后重试。"))
                .doFinally(signal -> {
                    if (!assistantReply.isEmpty()) {
                        historyRepository.append(sessionId, ROLE_ASSISTANT, assistantReply.toString());
                    }
                });
    }
    private List<ChatMessage> toAiPrompt(List<ConversationMessage> history) {
        return history.stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
    }

    /**
     * 构建含知识上下文的 system prompt。
     * 有命中结果时在基础 prompt 前注入【参考资料】块；无命中时返回基础 prompt。
     */
    private String buildSystemPrompt(List<KnowledgeSearchResult.Hit> hits) {
        return buildSystemPromptWithAddon(hits, null);
    }

    /**
     * 构建含知识上下文和领域专属说明的 system prompt。
     *
     * @param hits            RAG 检索命中结果
     * @param systemPromptAddon 领域专属 prompt 追加内容（null 时忽略）
     */
    private String buildSystemPromptWithAddon(List<KnowledgeSearchResult.Hit> hits,
                                               String systemPromptAddon) {
        String base = (systemPromptAddon != null && !systemPromptAddon.isBlank())
                ? systemPromptAddon + "\n" + BASE_SYSTEM_PROMPT
                : BASE_SYSTEM_PROMPT;
        if (hits == null || hits.isEmpty()) {
            return base;
        }
        StringBuilder ref = new StringBuilder("【参考资料】（请优先依据以下内容回答，无需在回答中标注来源编号）\n\n");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeSearchResult.Hit hit = hits.get(i);
            String label = (hit.getBreadcrumb() != null && !hit.getBreadcrumb().isBlank())
                    ? hit.getBreadcrumb() : "文档片段";
            String content = hit.getContent() != null ? hit.getContent() : "";
            ref.append("[").append(i + 1).append("] ").append(label).append("\n")
               .append(content).append("\n\n");
        }
        return ref.append("---\n").append(base).toString();
    }
}
