package com.aria.conversation.application.service;

import com.aria.conversation.application.service.tool.BuiltinTools;
import com.aria.conversation.application.service.tool.DomainSummary;
import com.aria.conversation.application.service.tool.DomainToolProviderFactory;
import com.aria.conversation.application.service.tool.InvocationParameters;
import com.aria.conversation.application.service.cancellation.CancellationRegistry;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.ai.SessionChatMemoryStore;
import com.aria.conversation.infrastructure.dit.config.IntentToolBinding;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 域 Agent 流式对话执行器。
 *
 * <p>为每次请求构建独立的 {@code DomainAssistant}，包含：
 * <ul>
 *   <li>RAG 增强的 system prompt（每次请求通过闭包计算）</li>
 *   <li>三层工具（MCP + 域 HTTP + 内置），由 {@link DomainToolProviderFactory} 组装</li>
 *   <li>token 流与工具事件流通过 {@link Flux#merge} 合并输出</li>
 * </ul>
 *
 * <p><b>per-request 构建：</b>每次调用必须重新 build DomainAssistant，
 * systemPrompt 依赖 RAG 闭包，builtinTools 依赖 per-request 会话上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainAgentService {

    private static final int CHAT_MEMORY_MAX_MESSAGES = 20;

    /**
     * AI 模型工厂，提供流式 ChatModel 实例
     */
    private final DynamicModelFactory modelFactory;
    /**
     * 领域配置仓储，用于查询域工具列表和所有域列表
     */
    private final DomainRepository domainRepo;
    /**
     * 域工具提供者工厂，按三层优先级组装 ToolProvider
     */
    private final DomainToolProviderFactory toolProviderFactory;
    /**
     * 对话记忆存储，按 sessionId 维护多轮上下文
     */
    private final SessionChatMemoryStore memoryStore;
    /**
     * 知识库 RAG 检索客户端
     */
    private final KnowledgeServiceClient knowledgeServiceClient;
    /**
     * JSON 序列化工具，用于构造内置工具的 SSE 事件载荷
     */
    private final ObjectMapper objectMapper;
    /**
     * 激活域 Redis 仓储，内置工具 switch_domain 使用
     */
    private final SessionDomainRepository sessionDomainRepo;
    /**
     * 域切换审计仓储，内置工具 switch_domain 使用
     */
    private final SessionDomainSwitchRepository domainSwitchRepo;
    /**
     * 会话队列服务，内置工具 transfer_to_agent 使用
     */
    private final SessionQueueService sessionQueueService;
    /**
     * 取消信号注册表，支持用户主动取消 Agent 生成
     */
    private final CancellationRegistry cancellationRegistry;

    /**
     * 流式域对话，发射 {@link ChatEvent} token 流和工具生命周期事件。
     *
     * @param sessionId   会话 ID（用作对话记忆 key）
     * @param domainCode  当前活跃域 code
     * @param userMessage 用户消息文本
     * @return AI token 事件与工具事件的合并流
     */
    public Flux<ChatEvent> streamChat(String sessionId, String domainCode, String userMessage) {
        // C2 修复：public 方法做防御性校验，防止调用方传入 null 导致 NPE
        if (userMessage == null || userMessage.isBlank()) {
            log.warn("[DomainAgent] streamChat 收到空消息 sessionId={}", sessionId);
            return Flux.just(ChatEvent.error("消息内容不能为空", objectMapper));
        }
        // M2 修复：截断后的用户消息仍可能含 PII（姓名/手机号等），只打消息长度
        log.info("[DomainAgent] start sessionId={} domain={} msgLength={}",
                sessionId, domainCode, userMessage.length());
        List<DomainSummary> allDomains = loadAllDomains();
        String systemPrompt = buildSystemPrompt(userMessage, buildDomainAddon(allDomains));
        return executeStream(sessionId, domainCode, userMessage, allDomains, systemPrompt);
    }

    /**
     * 多意图重载：将 intentCodes 注入 System Prompt，让 Agent 明确知道需要回答哪几个问题。
     *
     * @param intentCodes 当前消息的所有意图 code 列表（如 ["query_logistics", "cancel_order"]）
     */
    public Flux<ChatEvent> streamChat(String sessionId, String domainCode,
                                      String userMessage, List<String> intentCodes) {
        if (intentCodes == null || intentCodes.isEmpty()) {
            return streamChat(sessionId, domainCode, userMessage);
        }
        log.info("[DomainAgent] start (multi-intent) sessionId={} domain={} intentCount={}",
                sessionId, domainCode, intentCodes.size());
        List<DomainSummary> allDomains = loadAllDomains();
        String systemPrompt = buildSystemPrompt(userMessage, buildCombinedAddon(allDomains, intentCodes, domainCode));
        return executeStream(sessionId, domainCode, userMessage, allDomains, systemPrompt);
    }

    /**
     * 加载所有启用域摘要，供域切换工具和 System Prompt 使用。
     */
    private List<DomainSummary> loadAllDomains() {
        return domainRepo.findAllEnabledSummary().stream()
                .map(d -> new DomainSummary(d.getCode(), d.getDescription()))
                .toList();
    }

    /**
     * 构建 System Prompt：RAG hits + addon 拼接。
     */
    private String buildSystemPrompt(String userMessage, String addon) {
        List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(userMessage);
        return SystemPromptBuilder.build(hits, addon, null);
    }

    /**
     * 构建多意图 addon：域切换列表 + 意图感知指令。
     */
    private String buildCombinedAddon(List<DomainSummary> allDomains,
                                      List<String> intentCodes, String domainCode) {
        String domainAddon = buildDomainAddon(allDomains);
        String intentAddon = buildIntentAddon(intentCodes, domainCode);
        return (domainAddon != null ? domainAddon + "\n\n" : "") + intentAddon;
    }

    /**
     * 核心流式执行：构建 LangChain4j Agent 并合并 token + 工具事件流。
     */
    private Flux<ChatEvent> executeStream(String sessionId, String domainCode,
                                     String userMessage, List<DomainSummary> allDomains,
                                     String systemPrompt) {
        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
        // 获取当前域工具列表
        List<ToolConfig> domainTools = getToolsForDomain(domainCode);
        InvocationParameters params = new InvocationParameters(
                sessionId, domainCode, userMessage, allDomains, eventSink);
        // 构建内置工具
        BuiltinTools builtinTools = new BuiltinTools(
                params, sessionDomainRepo, domainSwitchRepo, objectMapper, sessionQueueService);

        // 注册取消句柄：turnId 隔离每一轮请求，避免同 sessionId 连续请求互相覆盖取消状态（I1 修复）
        CancellationRegistry.CancelHandle cancelHandle = cancellationRegistry.register(sessionId);
        String turnId = cancelHandle.turnId();

        DomainAssistant assistant = AiServices.builder(DomainAssistant.class)
                .streamingChatModel(modelFactory.getStreamingChatModel())
                .systemMessageProvider(id -> systemPrompt)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id).maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                        .chatMemoryStore(memoryStore).build())
                .toolProvider(toolProviderFactory.build(domainTools, eventSink, builtinTools, turnId))
                .build();

        Flux<ChatEvent> tokenFlux = assistant.chat(sessionId, userMessage)
                .map(content -> ChatEvent.token(content, objectMapper))
                // takeUntilOther：cancelHandle.trigger() 发射时截断 Flux，Reactor 向上传播 cancel
                // → LangChain4j reactor 模块关闭 LLM HTTP 连接
                .takeUntilOther(cancelHandle.trigger().asMono())
                .doFinally(sig -> {
                    // C1 修复：cancelled 事件必须在 tryEmitComplete() 之前发射。
                    // 若先 complete() 再 tryEmitNext(cancelled)，sink 已 terminated，emit 静默失败，
                    // 前端永远收不到 cancelled 事件，loading 状态无法停止。
                    // 取消检查必须在 unregister() 之前执行（否则标志已被清除无法判断）
                    boolean wasCancelled = cancellationRegistry.isCancelled(turnId);
                    cancellationRegistry.unregister(sessionId, turnId);
                    if (wasCancelled || sig == SignalType.CANCEL) {
                        // wasCancelled → 用户 API 取消（sig = ON_COMPLETE），事件可被前端消费
                        // CANCEL → SSE 客户端已断开，emit 可能静默失败，保留为语义完整性标记
                        eventSink.tryEmitNext(ChatEvent.cancelled(objectMapper));
                    }
                    // 打破 Flux.merge 完成依赖循环：
                    // merge 等 eventSink complete → eventSink 由 tokenFlux.doFinally 关闭 → merge 完成
                    eventSink.tryEmitComplete();
                });

        return Flux.merge(tokenFlux, eventSink.asFlux())
                .doOnError(e -> log.error("[DomainAgent] error sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage(), objectMapper)))
                .doFinally(signal ->
                        log.info("[DomainAgent] done sessionId={} signal={}", sessionId, signal));
    }

    /**
     * 根据意图 code 列表构建意图感知提示块，告知 LLM 需要逐一回答所有意图。
     *
     * <p>尝试从域配置加载意图名称（友好提示），失败时降级为直接显示 intentCode。
     */
    private String buildIntentAddon(List<String> intentCodes, String domainCode) {
        Map<String, String> intentNameMap = domainRepo.findByCode(domainCode)
                .map(dc -> dc.intents().stream()
                        .collect(Collectors.toMap(
                                ic -> ic.code().toLowerCase(),
                                ic -> ic.name() != null ? ic.name() : ic.code(),
                                (a, b) -> a)))
                .orElse(Map.of());

        StringBuilder sb = new StringBuilder("【多意图指令】当前用户消息包含以下意图，请逐一完整回答，不要遗漏：\n");
        for (String code : intentCodes) {
            String name = intentNameMap.getOrDefault(code.toLowerCase(), code);
            sb.append("- ").append(name).append("\n");
        }
        sb.append("请确保每个意图都有对应的回答。");
        return sb.toString();
    }

    private List<ToolConfig> getToolsForDomain(String domainCode) {
        return domainRepo.findByCode(domainCode)
                .map(dc -> dc.intents().stream()
                        .flatMap(ic -> ic.toolBindings().stream())
                        .map(IntentToolBinding::tool)
                        .distinct()
                        .toList())
                .orElse(List.of());
    }

    /**
     * 将所有启用域拼装为 system prompt addon，告知 LLM 可切换的域列表。
     */
    private String buildDomainAddon(List<DomainSummary> allDomains) {
        if (allDomains == null || allDomains.isEmpty()) return null;
        String domainList = allDomains.stream()
                .map(d -> d.code() + "（" + d.description() + "）")
                .collect(Collectors.joining("，"));
        return "当前可用服务域：" + domainList;
    }

    /**
     * LangChain4j AiService 流式对话接口（per-request 构建）。
     */
    private interface DomainAssistant {
        Flux<String> chat(@MemoryId String sessionId, @UserMessage String message);
    }
}
