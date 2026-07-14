package com.aria.conversation.application.service;

import com.aria.conversation.application.service.tool.BuiltinTools;
import com.aria.conversation.application.service.tool.DomainToolProviderFactory;
import com.aria.conversation.application.service.tool.DomainSummary;
import com.aria.conversation.application.service.tool.InvocationParameters;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.ai.SessionChatMemoryStore;
import com.aria.conversation.infrastructure.dit.config.IntentToolBinding;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
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

import java.util.List;
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

    /** AI 模型工厂，提供流式 ChatModel 实例 */
    private final DynamicModelFactory         modelFactory;
    /** 领域配置仓储，用于查询域工具列表和所有域列表 */
    private final DomainRepository            domainRepo;
    /** 域工具提供者工厂，按三层优先级组装 ToolProvider */
    private final DomainToolProviderFactory   toolProviderFactory;
    /** 对话记忆存储，按 sessionId 维护多轮上下文 */
    private final SessionChatMemoryStore      memoryStore;
    /** 知识库 RAG 检索客户端 */
    private final KnowledgeServiceClient      knowledgeServiceClient;
    /** JSON 序列化工具，用于构造内置工具的 SSE 事件载荷 */
    private final ObjectMapper                objectMapper;
    /** 激活域 Redis 仓储，内置工具 switch_domain 使用 */
    private final SessionDomainRepository     sessionDomainRepo;
    /** 域切换审计仓储，内置工具 switch_domain 使用 */
    private final SessionDomainSwitchRepository domainSwitchRepo;
    /** 会话队列服务，内置工具 transfer_to_agent 使用 */
    private final SessionQueueService         sessionQueueService;

    /**
     * LangChain4j AiService 流式对话接口（per-request 构建）。
     */
    private interface DomainAssistant {
        Flux<String> chat(@MemoryId String sessionId, @UserMessage String message);
    }

    /**
     * 流式域对话，发射 {@link ChatEvent} token 流和工具生命周期事件。
     *
     * @param sessionId   会话 ID（用作对话记忆 key）
     * @param domainCode  当前活跃域 code
     * @param userMessage 用户消息文本
     * @return AI token 事件与工具事件的合并流
     */
    public Flux<ChatEvent> streamChat(String sessionId, String domainCode, String userMessage) {
        log.info("[DomainAgent] start sessionId={} domain={} msg={}",
                sessionId, domainCode, userMessage.length() > 30
                        ? userMessage.substring(0, 30) + "…" : userMessage);

        List<DomainSummary> allDomains = domainRepo.findAllEnabledSummary().stream()
                .map(d -> new DomainSummary(d.getCode(), d.getDescription()))
                .toList();

        List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(userMessage);
        String systemPrompt = SystemPromptBuilder.build(hits, buildDomainAddon(allDomains), null);

        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();

        List<ToolConfig> domainTools = getToolsForDomain(domainCode);
        InvocationParameters params = new InvocationParameters(
                sessionId, domainCode, userMessage, allDomains, eventSink);
        BuiltinTools builtinTools = new BuiltinTools(
                params, sessionDomainRepo, domainSwitchRepo, objectMapper, sessionQueueService);

        DomainAssistant assistant = AiServices.builder(DomainAssistant.class)
                .streamingChatModel(modelFactory.getStreamingChatModel())
                .systemMessageProvider(id -> systemPrompt)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id).maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                        .chatMemoryStore(memoryStore).build())
                .toolProvider(toolProviderFactory.build(domainTools, eventSink, builtinTools))
                .build();

        Flux<ChatEvent> tokenFlux = assistant.chat(sessionId, userMessage)
                .map(content -> ChatEvent.token(content, objectMapper))
                .doFinally(signal -> {
                    log.info("[DomainAgent] done sessionId={} signal={}", sessionId, signal);
                    eventSink.tryEmitComplete();
                });

        return Flux.merge(tokenFlux, eventSink.asFlux())
                .doOnError(e -> log.error("[DomainAgent] error sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage(), objectMapper)));
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
}
