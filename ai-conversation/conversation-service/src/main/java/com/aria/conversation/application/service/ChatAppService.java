package com.aria.conversation.application.service;

import com.aria.conversation.application.service.payload.TokenPayload;
import com.aria.conversation.application.service.payload.TransferPayload;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.SessionQueueItem;
import com.aria.conversation.domain.service.DomainRoutingService;
import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.domain.service.IntentService;
import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.infrastructure.dit.domain.DomainSwitchRecord;
import com.aria.conversation.infrastructure.dit.domain.SwitchType;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
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
import java.util.Map;
import java.util.Optional;

/**
 * 对话应用服务。
 * 编排多轮对话历史维护（委托给 ConversationHistoryRepository）、
 * 知识库检索（RAG）和 AI 回复生成。
 *
 * <p>域对话（有 domainCode）委托给 {@link DomainAgentService}，
 * 通用 FAQ 流程（无 domainCode）在本类内部完成。
 */
@Slf4j
@Service
public class ChatAppService {

    private static final String ROLE_USER      = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private static final String AGENT_HINT_MSG = "（消息已发送给人工客服）";
    private static final String OUT_OF_SCOPE_REPLY =
            "抱歉，我是专业的客服助手，只能回答业务相关的问题，无法帮您解答这个问题。";
    private static final String TRANSFER_AUTO_REASON  = "系统识别到用户需要人工服务";
    private static final String TRANSFER_DEFAULT_TAG  = "咨询";
    /** FAQ 路径转人工时的 intentCode 占位符，区别于 DIT 路径使用真实意图 code */
    private static final String FAQ_TRANSFER_INTENT_CODE = "faq_transfer";

    private static final String BASE_SYSTEM_PROMPT = """
            你是一名专业的智能客服助手。
            请用简洁、友好的语言回答用户问题。
            如果涉及订单、退款等敏感操作，引导用户验证身份。
            回答要简明扼要，避免冗长说明。
            """;

    private final DynamicModelFactory aiClient;
    private final ConversationHistoryRepository historyRepository;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final IntentService intentClassifier;
    private final SessionQueueService sessionQueueService;
    private final ObjectMapper objectMapper;
    private final SessionDomainRepository sessionDomainRepo;
    private final SessionDomainSwitchRepository domainSwitchRepo;
    private final DomainRoutingService domainRoutingService;
    private final DomainAgentService domainAgentService;

    public ChatAppService(DynamicModelFactory aiClient,
                          ConversationHistoryRepository historyRepository,
                          KnowledgeServiceClient knowledgeServiceClient,
                          IntentService intentClassifier,
                          SessionQueueService sessionQueueService,
                          ObjectMapper objectMapper,
                          SessionDomainRepository sessionDomainRepo,
                          SessionDomainSwitchRepository domainSwitchRepo,
                          DomainRoutingService domainRoutingService,
                          DomainAgentService domainAgentService) {
        this.aiClient            = aiClient;
        this.historyRepository   = historyRepository;
        this.knowledgeServiceClient = knowledgeServiceClient;
        this.intentClassifier    = intentClassifier;
        this.sessionQueueService = sessionQueueService;
        this.objectMapper        = objectMapper;
        this.sessionDomainRepo   = sessionDomainRepo;
        this.domainSwitchRepo    = domainSwitchRepo;
        this.domainRoutingService = domainRoutingService;
        this.domainAgentService  = domainAgentService;
    }

    // -------------------------------------------------------
    // 统一对话入口（DDD：Controller 只调这一个方法）
    // -------------------------------------------------------

    /**
     * 统一流式对话入口，返回 {@link ChatEvent} 流供 Controller 转换为 SSE。
     *
     * <p>所有路由决策（人工接入判断、DomainAgent、通用 FAQ）全部在此方法内完成，
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
            return Flux.just(ChatEvent.token(AGENT_HINT_MSG, objectMapper));
        }

        // 有 domainCode → 域路由 + DomainAgentService
        if (StringUtils.isNotBlank(domainCode)) {
            // 将阻塞操作（Redis 读写、小模型推理、意图分类）放到 boundedElastic 线程池执行，
            // 避免阻塞 Netty event-loop；解析完成后切换为流式 AI 回复
            return Mono.fromCallable(() -> {
                        String activeDomain = resolveActiveDomain(sessionId, message, domainCode);
                        // 意图规则层：在进入 DomainAgentService 前先做快速拦截
                        // TRANSFER_REQUEST / COMPLAINT 直接转人工，不消耗 function-calling token
                        IntentResult intent = intentClassifier.classify(message);
                        return new DomainRouteData(activeDomain, intent);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(route -> {
                        if (route.intent().requiresTransfer()) {
                            log.info("[Chat] domain 路径意图拦截 sessionId={} intent={}",
                                    sessionId, route.intent().intent());
                            return handleTransferIntent(sessionId, route.intent());
                        }
                        return domainAgentService.streamChat(sessionId, route.activeDomain(), message);
                    });
        }

        // 通用 FAQ 流程：RAG + 意图路由 + LLM，含 sources event
        return streamFaq(sessionId, message);
    }

    // -------------------------------------------------------
    // 域路由编排（从 stream 中提取，职责单一化）
    // -------------------------------------------------------

    /**
     * 解析当前会话的活跃域，完整编排三步流程：
     * <ol>
     *   <li>幂等初始化 AI_CHAT 会话记录</li>
     *   <li>读取或首次写入 session 激活域</li>
     *   <li>ROUTER 小模型域路由决策</li>
     * </ol>
     *
     * <p><b>注意：</b>本方法包含 Redis 读写和小模型推理等阻塞操作，
     * 必须在 {@link Schedulers#boundedElastic()} 线程中调用。
     *
     * @param sessionId  会话 ID
     * @param message    用户原始消息（用于域切换记录和路由上下文）
     * @param domainCode 入口传入的默认域标识
     * @return 最终确定的活跃域编码
     */
    private String resolveActiveDomain(String sessionId, String message, String domainCode) {
        // Step 0: 幂等初始化 AI_CHAT 会话记录（首条消息时建档，已存在则跳过）
        sessionQueueService.initAiChatSession(sessionId);

        // Step 1: 读取已有激活域，首次进入时以 domainCode 作为初始域并记录 INITIAL 切换
        String activeDomain = resolveOrInitDomain(sessionId, message, domainCode);

        // Step 2: 小模型路由决策，判断是否需要切换到其他域
        return routeDomainIfNeeded(sessionId, message, activeDomain);
    }

    /**
     * 读取 session 当前激活域；若不存在（首次进入），则以 {@code domainCode} 初始化
     * 并写入一条 {@link SwitchType#INITIAL} 类型的域切换记录。
     *
     * @param sessionId  会话 ID
     * @param message    用户消息（记录到域切换日志中）
     * @param domainCode 默认域标识，仅在首次进入时使用
     * @return 当前激活域编码
     */
    private String resolveOrInitDomain(String sessionId, String message, String domainCode) {
        Optional<String> existing = sessionDomainRepo.find(sessionId);
        if (existing.isPresent()) {
            log.debug("[DomainRoute] sessionId={} 已有激活域={}", sessionId, existing.get());
            return existing.get();
        }
        // 首次进入：绑定初始域并记录切换日志
        saveDomainSwitch(sessionId, null, domainCode, SwitchType.INITIAL, message, "用户进入服务入口");
        log.info("[DomainRoute] sessionId={} 初始化激活域={}", sessionId, domainCode);
        return domainCode;
    }

    /**
     * 使用 ROUTER 小模型进行域路由决策。
     * <ul>
     *   <li>若模型建议切换 → 更新 session 激活域并记录 {@link SwitchType#ROUTER_MODEL} 切换</li>
     *   <li>若模型建议保持 → 返回当前域不变</li>
     *   <li>若路由过程异常 → 降级保持当前域，打印 warn 日志</li>
     * </ul>
     *
     * @param sessionId    会话 ID
     * @param message      用户原始消息
     * @param activeDomain 当前激活域
     * @return 路由决策后的活跃域编码
     */
    private String routeDomainIfNeeded(String sessionId, String message, String activeDomain) {
        try {
            long currentTimeMillis = System.currentTimeMillis();
            // 获取最近对话历史作为路由上下文（小模型需要多轮信息判断意图漂移）
            List<ConversationMessage> recentHistory = historyRepository.findAll(sessionId);
            DomainRoutingService.RouteResult routing =
                    domainRoutingService.route(message, activeDomain, recentHistory);
            log.info("[DomainRoute] sessionId={} 路由决策={},耗时{}ms", sessionId, routing, System.currentTimeMillis() - currentTimeMillis);
            if (routing.shouldSwitch()) {
                String newDomain = routing.suggestedDomain();
                log.info("[DomainRoute] sessionId={} 域切换 {} -> {} (reason=小模型检测)",
                        sessionId, activeDomain, newDomain);
                saveDomainSwitch(sessionId, activeDomain, newDomain,
                        SwitchType.ROUTER_MODEL, message, "小模型检测切换");
                return newDomain;
            }

            log.debug("[DomainRoute] sessionId={} 保持当前域={}", sessionId, activeDomain);
            return activeDomain;
        } catch (Exception e) {
            // 路由失败不应阻断对话流程，降级保持当前域继续处理
            log.warn("[DomainRoute] sessionId={} 路由异常，降级保持当前域={}", sessionId, activeDomain, e);
            return activeDomain;
        }
    }

    /**
     * 统一保存域绑定关系并记录域切换日志。
     * <p>封装了 {@link SessionDomainRepository#save} 和
     * {@link SessionDomainSwitchRepository#record} 的组合操作，
     * 确保每次域变更都同时更新绑定和审计记录。
     *
     * @param sessionId  会话 ID
     * @param fromDomain 切换前的域（首次进入时为 null）
     * @param toDomain   切换后的目标域
     * @param switchType 切换类型常量（{@link SwitchType}）
     * @param message    触发切换的用户消息
     * @param reason     切换原因描述
     */
    private void saveDomainSwitch(String sessionId, String fromDomain, String toDomain,
                                  String switchType, String message, String reason) {
        sessionDomainRepo.save(sessionId, toDomain);
        domainSwitchRepo.record(new DomainSwitchRecord(
                sessionId, fromDomain, toDomain, switchType, message, reason, null));
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

    /**
     * 查询会话当前状态（供 ChatController GET /api/v1/chat/state 使用）。
     *
     * @param sessionId 会话 ID
     * @return 当前会话状态
     */
    public SessionStatus getSessionStatus(String sessionId) {
        return sessionQueueService.getSessionStatus(sessionId);
    }

    // -------------------------------------------------------
    // 对话主流程
    // -------------------------------------------------------

    /**
     * 检索与用户问题相关的知识块（RAG 第一步）。
     * 运行于 Spring MVC 阻塞线程，直接调用，不使用响应式包装。
     */
    public List<KnowledgeSearchResult.Hit> searchHits(String userMessage) {
        return knowledgeServiceClient.search(userMessage);
    }

    /**
     * 通用 FAQ 流程：RAG + 意图路由 + LLM，包含 sources event。
     * 返回 {@link ChatEvent} 流，知识库命中时先发 sources 再发 AI token。
     */
    private Flux<ChatEvent> streamFaq(String sessionId, String message) {
        return Mono.fromCallable(() -> {
                    // 幂等初始化 AI_CHAT 会话记录（首条消息时建档，已存在则跳过）
                    sessionQueueService.initAiChatSession(sessionId);
                    historyRepository.append(sessionId, ROLE_USER, message);
                    List<KnowledgeSearchResult.Hit> hits = searchHits(message);
                    IntentResult intent = intentClassifier.classify(message);
                    log.debug("[Chat] sessionId={} intent={} confidence={}",
                            sessionId, intent.intent(), intent.confidence());
                    return new FaqRouteData(hits, intent);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(route -> buildFaqEventStream(sessionId, message, route));
    }

    /**
     * 将 FAQ 路由数据转换为 SSE 事件流。
     */
    private Flux<ChatEvent> buildFaqEventStream(String sessionId, String message, FaqRouteData route) {
        IntentResult intent = route.intent();
        List<KnowledgeSearchResult.Hit> hits = route.hits();

        // 路由：需要转人工
        if (intent.requiresTransfer()) {
            return handleTransferIntent(sessionId, intent);
        }

        // 路由：超出业务范围
        if (intent.intent() == IntentType.OUT_OF_SCOPE) {
            historyRepository.append(sessionId, ROLE_ASSISTANT, OUT_OF_SCOPE_REPLY);
            return Flux.just(ChatEvent.token(OUT_OF_SCOPE_REPLY, objectMapper));
        }

        // 路由：闲聊 → 跳过 RAG
        List<KnowledgeSearchResult.Hit> effectiveHits = intent.skipRag() ? List.of() : hits;
        String systemPrompt = SystemPromptBuilder.build(effectiveHits, null, BASE_SYSTEM_PROMPT);
        List<ChatMessage> aiPrompt = toAiPrompt(historyRepository.findAll(sessionId));
        StringBuilder assistantReply = new StringBuilder();

        Flux<ChatEvent> aiStream = aiClient.streamChat(aiPrompt, systemPrompt)
                .map(content -> {
                    assistantReply.append(content);
                    return ChatEvent.token(content, objectMapper);
                })
                .doOnError(e -> log.warn("[AI] 流式对话失败 sessionId={}", sessionId, e))
                // 与 DomainAgentService 对齐：LLM 异常统一走 error 事件（JSON 信封），前端只需一套错误分支
                .onErrorResume(e -> Flux.just(ChatEvent.error("抱歉，AI 服务暂时不可用，请稍后重试。", objectMapper)))
                .doFinally(signal -> {
                    if (!assistantReply.isEmpty()) {
                        historyRepository.append(sessionId, ROLE_ASSISTANT, assistantReply.toString());
                    }
                });

        // 无命中 / 闲聊跳过 RAG → 直接返回 AI 流
        if (hits.isEmpty() || intent.skipRag()) {
            return aiStream;
        }

        // 有命中：用 switchOnFirst 延迟 sources，仅在正常 AI 回复路径前置
        ChatEvent sourcesEvent = ChatEvent.sources(buildSourcesJson(hits));
        return aiStream.switchOnFirst((signal, flux) -> {
            if (signal.hasValue()) {
                ChatEvent first = signal.get();
                String type = first != null ? first.eventType() : null;
                if (ChatEvent.EventType.TRANSFER.equals(type)
                        || ChatEvent.EventType.ERROR.equals(type)) {
                    return flux; // 语义事件路径跳过 sources
                }
            }
            return Flux.concat(Flux.just(sourcesEvent), flux);
        });
    }

    /** FAQ 路由阶段的中间结果，携带 hits 和意图分类结果。 */
    private record FaqRouteData(List<KnowledgeSearchResult.Hit> hits, IntentResult intent) {}

    /** domain 路径路由中间结果，携带活跃域和意图分类结果。 */
    private record DomainRouteData(String activeDomain, IntentResult intent) {}

    /**
     * 处理转人工意图（FAQ 路径和 domain 路径共用）。
     * 入队并发出 TRANSFER 语义事件；入队失败不影响事件发射。
     */
    private Flux<ChatEvent> handleTransferIntent(String sessionId, IntentResult intent) {
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
        try {
            String transferJson = objectMapper.writeValueAsString(
                    new TransferPayload(FAQ_TRANSFER_INTENT_CODE, reply));
            return Flux.just(ChatEvent.transfer(transferJson));
        } catch (JsonProcessingException e) {
            log.warn("[Chat] transfer payload 序列化失败 sessionId={}", sessionId, e);
            return Flux.just(ChatEvent.token(reply, objectMapper));
        }
    }

    /**
     * 将 hits 序列化为 JSON 数组，格式：[{"docId":"...","label":"..."}]。
     */
    public String buildSourcesJson(List<KnowledgeSearchResult.Hit> hits) {
        if (hits.isEmpty()) {
            return "[]";
        }
        List<Map<String, String>> sources = hits.stream()
                .map(h -> {
                    String label = (h.getBreadcrumb() != null && !h.getBreadcrumb().isBlank())
                            ? h.getBreadcrumb() : "文档片段";
                    return Map.of(
                            "docId", h.getDocId() != null ? h.getDocId() : "",
                            "label", label
                    );
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            log.warn("[Chat] sources JSON 序列化失败，降级返回空数组", e);
            return "[]";
        }
    }

    /**
     * 非流式对话，返回完整回复文本。
     */
    public String chat(String sessionId, String userMessage) {
        historyRepository.append(sessionId, ROLE_USER, userMessage);
        List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(userMessage);
        String systemPrompt = SystemPromptBuilder.build(hits, null, BASE_SYSTEM_PROMPT);
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
     * @deprecated 新代码应直接调用 {@link #stream}。
     *             本方法仅保留用于非 SSE 场景（如内部调用）的向后兼容，
     *             后续版本将被移除。
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public Flux<String> streamChat(String sessionId, String userMessage) {
        return streamFaq(sessionId, userMessage)
                .flatMap(e -> {
                    // token 事件：data 是 {"content":"..."} JSON 信封，解出 content
                    if (e.eventType() == null) {
                        try {
                            TokenPayload token = objectMapper.readValue(e.data(), TokenPayload.class);
                            return Flux.just(token.content() != null ? token.content() : "");
                        } catch (Exception ex) {
                            log.warn("[Chat] token payload 解析失败，降级返回原始 data", ex);
                            return Flux.just(e.data());
                        }
                    }
                    if (ChatEvent.EventType.TRANSFER.equals(e.eventType())) {
                        try {
                            TransferPayload payload = objectMapper.readValue(e.data(), TransferPayload.class);
                            return Flux.just(payload.message() != null ? payload.message() : e.data());
                        } catch (Exception ex) {
                            log.warn("[Chat] transfer payload 解析失败，降级返回原始 data", ex);
                            return Flux.just(e.data());
                        }
                    }
                    return Flux.empty();
                });
    }

    private List<ChatMessage> toAiPrompt(List<ConversationMessage> history) {
        return history.stream()
                .map(m -> new ChatMessage(m.role(), m.content()))
                .toList();
    }

}
