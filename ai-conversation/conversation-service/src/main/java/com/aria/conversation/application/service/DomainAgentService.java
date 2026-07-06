package com.aria.conversation.application.service;

import com.aria.conversation.application.service.payload.ToolCallPayload;
import com.aria.conversation.application.service.payload.ToolDonePayload;
import com.aria.conversation.infrastructure.ai.BuiltinToolNames;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.ai.SessionChatMemoryStore;
import com.aria.conversation.infrastructure.dit.config.IntentToolBinding;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.domain.DomainDO;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.aria.conversation.infrastructure.dit.pipeline.HttpToolRunner;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 LangChain4j function-calling 的域对话服务。
 *
 * <p>替代原 {@code DitPipeline}，为每次请求构建 {@code DomainAssistant}，包含：
 * <ul>
 *   <li>RAG 增强的 system prompt（每次请求通过闭包计算）</li>
 *   <li>域范围的 HTTP 工具（来自 {@code DomainConfig}）</li>
 *   <li>内置 {@code switch_domain} 和 {@code transfer_to_agent} 工具</li>
 *   <li>{@code tool_call}/{@code tool_done} SSE 事件通过 {@link Sinks.Many} 从
 *       {@code ToolExecutor} lambda 中直接发射</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainAgentService {

    private static final int CHAT_MEMORY_MAX_MESSAGES = 20;

    private final DynamicModelFactory modelFactory;
    private final DomainRepository domainRepo;
    private final HttpToolRunner httpToolRunner;
    private final SessionChatMemoryStore memoryStore;
    private final KnowledgeClient knowledgeClient;
    private final ObjectMapper objectMapper;

    /**
     * LangChain4j AiService 流式对话接口。
     * 使用 langchain4j-reactor 适配器返回 {@code Flux<String>}。
     */
    private interface DomainAssistant {
        Flux<String> chat(@MemoryId String sessionId, @UserMessage String message);
    }

    /**
     * 流式域对话，发射 {@link ChatEvent} 令牌流和工具生命周期事件。
     *
     * @param sessionId   会话 ID（用作对话记忆 key）
     * @param domainCode  当前活跃域 code
     * @param userMessage 用户消息文本
     * @return AI 令牌事件与工具事件的合并流
     */
    public Flux<ChatEvent> streamChat(String sessionId, String domainCode, String userMessage) {
        log.debug("[DomainAgent] start sessionId={} domain={}", sessionId, domainCode);

        // 1. RAG：同步检索知识库（在调用线程上执行）
        List<KnowledgeSearchResult.Hit> hits = knowledgeClient.search(userMessage);
        String systemPrompt = SystemPromptBuilder.build(hits);

        // 2. Sink：用于发射 tool_call / tool_done / domain_switch / transfer 事件
        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();

        // 3. 收集域范围内的工具列表
        List<ToolConfig> domainTools = getToolsForDomain(domainCode);

        // 4. 构建 ToolProvider（域工具 + 内置工具）
        ToolProvider toolProvider = buildToolProvider(domainCode, domainTools, eventSink);

        // 5. 构建每次请求独立的 DomainAssistant
        //    systemMessageProvider 使用闭包，确保每次请求获取独立的 RAG prompt
        DomainAssistant assistant = AiServices.builder(DomainAssistant.class)
                .streamingChatModel(modelFactory.getStreamingChatModel())
                .systemMessageProvider(id -> systemPrompt)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                        .chatMemoryStore(memoryStore)
                        .build())
                .toolProvider(toolProvider)
                .build();

        // 6. 合并 AI 令牌流与工具事件流
        Flux<ChatEvent> tokenFlux = assistant.chat(sessionId, userMessage)
                .map(ChatEvent::data)
                .doFinally(signal -> eventSink.tryEmitComplete());

        return Flux.merge(tokenFlux, eventSink.asFlux())
                .doOnError(e -> log.error("[DomainAgent] error sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage())));
    }

    // -------------------------------------------------------
    // 工具集合
    // -------------------------------------------------------

    private List<ToolConfig> getToolsForDomain(String domainCode) {
        return domainRepo.findByCode(domainCode)
                .map(dc -> dc.intents().stream()
                        .flatMap(ic -> ic.toolBindings().stream())
                        .map(IntentToolBinding::tool)
                        .distinct()
                        .toList())
                .orElse(List.of());
    }

    // -------------------------------------------------------
    // ToolProvider 构造
    // -------------------------------------------------------

    private ToolProvider buildToolProvider(String domainCode,
                                            List<ToolConfig> tools,
                                            Sinks.Many<ChatEvent> eventSink) {
        // 预计算域列表，用于 switch_domain 描述
        List<DomainDO> allDomains = domainRepo.findAllEnabledSummary();
        String domainDesc = allDomains.stream()
                .map(d -> d.getCode() + "(" + d.getDescription() + ")")
                .collect(Collectors.joining(", "));

        return request -> {
            Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();

            // 域内业务工具
            for (ToolConfig tc : tools) {
                toolMap.put(buildToolSpec(tc), buildHttpToolExecutor(tc, eventSink));
            }

            // 内置工具：switch_domain
            ToolSpecification switchDomainSpec = ToolSpecification.builder()
                    .name(BuiltinToolNames.SWITCH_DOMAIN)
                    .description("当用户问题与当前服务域无关时调用。可用域：" + domainDesc)
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("target_domain_code", "目标域 code")
                            .addStringProperty("reason", "切换原因")
                            .required("target_domain_code")
                            .build())
                    .build();
            toolMap.put(switchDomainSpec, buildSwitchDomainExecutor(domainCode, eventSink));

            // 内置工具：transfer_to_agent
            ToolSpecification transferSpec = ToolSpecification.builder()
                    .name(BuiltinToolNames.TRANSFER_TO_AGENT)
                    .description("当用户明确要求转接人工客服时调用")
                    .parameters(JsonObjectSchema.builder().build())
                    .build();
            toolMap.put(transferSpec, buildTransferExecutor(eventSink));

            return new ToolProviderResult(toolMap);
        };
    }

    // -------------------------------------------------------
    // ToolExecutor 工厂方法
    // -------------------------------------------------------

    private ToolExecutor buildHttpToolExecutor(ToolConfig tc, Sinks.Many<ChatEvent> eventSink) {
        return (ToolExecutionRequest req, Object memId) -> {
            try {
                // 通知前端：工具正在执行
                eventSink.tryEmitNext(ChatEvent.toolCall(
                        objectMapper.writeValueAsString(ToolCallPayload.running(tc.code()))));

                Map<String, Object> args = parseArguments(req.arguments());
                ToolCallResult result = httpToolRunner.execute(tc, args, Map.of());
                log.info("[DomainAgent] tool={} status={}", tc.code(), result.getStatus());

                // 通知前端：工具执行完成
                eventSink.tryEmitNext(ChatEvent.toolDone(
                        objectMapper.writeValueAsString(ToolDonePayload.from(result))));

                return result.isSuccess()
                        ? result.getResponse()
                        : "工具执行失败: " + result.getErrorMsg();
            } catch (Exception e) {
                log.error("[DomainAgent] tool error tool={}", tc.code(), e);
                return "工具执行失败: " + e.getMessage();
            }
        };
    }

    private ToolExecutor buildSwitchDomainExecutor(String currentDomain,
                                                     Sinks.Many<ChatEvent> eventSink) {
        return (ToolExecutionRequest req, Object memId) -> {
            try {
                Map<String, Object> args = parseArguments(req.arguments());
                String targetDomain = (String) args.get("target_domain_code");
                if (targetDomain == null || targetDomain.isBlank()) {
                    log.warn("[DomainAgent] switch_domain missing target_domain_code");
                    return "域切换参数缺失，保持当前服务域。";
                }
                String reason = (String) args.getOrDefault("reason", "");
                log.info("[DomainAgent] switch_domain {} → {} reason={}", currentDomain, targetDomain, reason);
                eventSink.tryEmitNext(ChatEvent.domainSwitch(targetDomain));
            } catch (Exception e) {
                log.warn("[DomainAgent] switch_domain parse error", e);
            }
            return "正在为您切换到对应服务...";
        };
    }

    private ToolExecutor buildTransferExecutor(Sinks.Many<ChatEvent> eventSink) {
        return (ToolExecutionRequest req, Object memId) -> {
            eventSink.tryEmitNext(ChatEvent.transfer("{\"intentCode\":\"agent_transfer\"}"));
            return "已为您转接人工客服，请稍候。";
        };
    }

    // -------------------------------------------------------
    // 工具规格（ToolSpecification）构造
    // -------------------------------------------------------

    private ToolSpecification buildToolSpec(ToolConfig tc) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
        try {
            if (tc.paramSchema() != null && !tc.paramSchema().isBlank()) {
                Map<String, Object> schema = objectMapper.readValue(
                        tc.paramSchema(), new TypeReference<>() {});
                @SuppressWarnings("unchecked")
                Map<String, Object> properties =
                        (Map<String, Object>) schema.getOrDefault("properties", Map.of());
                properties.forEach((name, def) -> {
                    String desc = def instanceof Map<?, ?> m
                            ? String.valueOf(m.get("description") != null ? m.get("description") : "")
                            : "";
                    schemaBuilder.addProperty(name,
                            JsonStringSchema.builder().description(desc).build());
                });
                @SuppressWarnings("unchecked")
                List<String> required = (List<String>) schema.get("required");
                if (required != null && !required.isEmpty()) {
                    schemaBuilder.required(required);
                }
            }
        } catch (Exception e) {
            log.error("[DomainAgent] paramSchema 解析失败，工具参数定义将为空 tool={}", tc.code(), e);
        }
        return ToolSpecification.builder()
                .name(tc.code())
                .description(tc.description())
                .parameters(schemaBuilder.build())
                .build();
    }



    // -------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------

    private Map<String, Object> parseArguments(String arguments) throws Exception {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(arguments, new TypeReference<>() {});
    }
}
