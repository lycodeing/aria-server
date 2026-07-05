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
 * LangChain4j function-calling domain agent service.
 *
 * <p>Replaces {@code DitPipeline} for the DIT path. Builds a per-request
 * {@code DomainAssistant} with:
 * <ul>
 *   <li>RAG-enriched system prompt (computed per-request via closure)</li>
 *   <li>Domain-scoped HTTP tools from {@code DomainConfig}</li>
 *   <li>Built-in {@code switch_domain} and {@code transfer_to_agent} tools</li>
 *   <li>{@code tool_call}/{@code tool_done} SSE events emitted directly from the
 *       {@code ToolExecutor} lambdas via a {@link Sinks.Many}</li>
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
     * LangChain4j AiService interface for streaming domain chat.
     * Uses Reactor adapter (langchain4j-reactor) to return {@code Flux<String>}.
     */
    private interface DomainAssistant {
        Flux<String> chat(@MemoryId String sessionId, @UserMessage String message);
    }

    /**
     * Stream domain-aware chat, emitting {@link ChatEvent} tokens and tool events.
     *
     * @param sessionId  conversation session ID (used as chat memory key)
     * @param domainCode active domain code
     * @param userMessage user's message text
     * @return merged flux of AI token events and tool lifecycle events
     */
    public Flux<ChatEvent> streamChat(String sessionId, String domainCode, String userMessage) {
        log.debug("[DomainAgent] start sessionId={} domain={}", sessionId, domainCode);

        // 1. RAG: retrieve relevant knowledge hits synchronously (runs on caller's thread)
        List<KnowledgeSearchResult.Hit> hits = knowledgeClient.search(userMessage);
        String systemPrompt = buildSystemPrompt(hits);

        // 2. Sink for tool_call / tool_done / domain_switch / transfer events
        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();

        // 3. Collect domain-scoped tools
        List<ToolConfig> domainTools = getToolsForDomain(domainCode);

        // 4. Build ToolProvider with domain tools + builtins
        ToolProvider toolProvider = buildToolProvider(domainCode, domainTools, eventSink);

        // 5. Build per-request DomainAssistant
        //    systemMessageProvider uses a closure so each request gets its own RAG prompt.
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

        // 6. Merge AI token stream with tool-event stream
        Flux<ChatEvent> tokenFlux = assistant.chat(sessionId, userMessage)
                .map(ChatEvent::data)
                .doFinally(signal -> eventSink.tryEmitComplete());

        return Flux.merge(tokenFlux, eventSink.asFlux())
                .doOnError(e -> log.error("[DomainAgent] error sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage())));
    }

    // -------------------------------------------------------
    // Tool collection
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
    // ToolProvider construction
    // -------------------------------------------------------

    private ToolProvider buildToolProvider(String domainCode,
                                            List<ToolConfig> tools,
                                            Sinks.Many<ChatEvent> eventSink) {
        // Pre-compute domain list for switch_domain description
        List<DomainDO> allDomains = domainRepo.findAllEnabledSummary();
        String domainDesc = allDomains.stream()
                .map(d -> d.getCode() + "(" + d.getDescription() + ")")
                .collect(Collectors.joining(", "));

        return request -> {
            Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();

            // Business tools bound to this domain
            for (ToolConfig tc : tools) {
                toolMap.put(buildToolSpec(tc), buildHttpToolExecutor(tc, eventSink));
            }

            // Built-in: switch_domain
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

            // Built-in: transfer_to_agent
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
    // Individual ToolExecutor factories
    // -------------------------------------------------------

    private ToolExecutor buildHttpToolExecutor(ToolConfig tc, Sinks.Many<ChatEvent> eventSink) {
        return (ToolExecutionRequest req, Object memId) -> {
            try {
                // Notify frontend: tool is running
                eventSink.tryEmitNext(ChatEvent.toolCall(
                        objectMapper.writeValueAsString(ToolCallPayload.running(tc.code()))));

                Map<String, Object> args = parseArguments(req.arguments());
                ToolCallResult result = httpToolRunner.execute(tc, args, Map.of());
                log.info("[DomainAgent] tool={} status={}", tc.code(), result.getStatus());

                // Notify frontend: tool completed
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
    // ToolSpecification construction from ToolConfig
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
            log.warn("[DomainAgent] paramSchema parse error tool={}", tc.code(), e);
        }
        return ToolSpecification.builder()
                .name(tc.code())
                .description(tc.description())
                .parameters(schemaBuilder.build())
                .build();
    }

    // -------------------------------------------------------
    // System prompt construction
    // -------------------------------------------------------

    private String buildSystemPrompt(List<KnowledgeSearchResult.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        if (hits != null && !hits.isEmpty()) {
            sb.append("【参考资料】（请优先依据以下内容回答，无需在回答中标注来源编号）\n\n");
            for (int i = 0; i < hits.size(); i++) {
                KnowledgeSearchResult.Hit h = hits.get(i);
                String label = (h.getBreadcrumb() != null && !h.getBreadcrumb().isBlank())
                        ? h.getBreadcrumb() : "文档片段";
                sb.append("[").append(i + 1).append("] ").append(label).append("\n")
                  .append(h.getContent() != null ? h.getContent() : "").append("\n\n");
            }
            sb.append("---\n");
        }
        sb.append("你是一名专业的智能客服助手。请用简洁、友好的语言回答用户问题。");
        return sb.toString();
    }

    // -------------------------------------------------------
    // Utilities
    // -------------------------------------------------------

    private Map<String, Object> parseArguments(String arguments) throws Exception {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(arguments, new TypeReference<>() {});
    }
}
