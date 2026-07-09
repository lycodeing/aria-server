package com.aria.conversation.application.service;

import com.aria.conversation.application.service.payload.ToolCallPayload;
import com.aria.conversation.application.service.payload.ToolDonePayload;
import com.aria.conversation.application.service.tool.BuiltinTools;
import com.aria.conversation.application.service.tool.DomainSummary;
import com.aria.conversation.application.service.tool.InvocationParameters;
import com.aria.conversation.infrastructure.ai.DynamicModelFactory;
import com.aria.conversation.infrastructure.ai.SessionChatMemoryStore;
import com.aria.conversation.infrastructure.ai.mcp.McpClientRegistry;
import com.aria.conversation.infrastructure.dit.config.IntentToolBinding;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.aria.conversation.infrastructure.dit.pipeline.HttpToolRunner;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.aria.conversation.infrastructure.knowledge.KnowledgeServiceClient;
import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final ObjectMapper objectMapper;
    private final SessionDomainRepository sessionDomainRepo;
    private final SessionDomainSwitchRepository domainSwitchRepo;
    /** MCP 工具注册表，提供来自外部 MCP 服务端的动态工具 */
    private final McpClientRegistry mcpClientRegistry;
    /** 转接入队服务，由 BuiltinTools.transferToAgent() 直接调用 */
    private final SessionQueueService sessionQueueService;

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

        // 1. 预查询所有域，在 application 层转为 DomainSummary（隔离持久化实体流转）
        List<DomainSummary> allDomains = domainRepo.findAllEnabledSummary().stream()
                .map(d -> new DomainSummary(d.getCode(), d.getDescription()))
                .toList();
        String domainAddon = buildDomainAddon(allDomains);

        // 2. RAG + system prompt（域列表通过 addon 写入，LLM 从 system prompt 获知可切换域）
        List<KnowledgeSearchResult.Hit> hits = knowledgeServiceClient.search(userMessage);
        String systemPrompt = SystemPromptBuilder.build(hits, domainAddon, null);

        // 3. Sink：用于发射 tool_call / tool_done / domain_switch / transfer 事件
        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();

        // 4. 域范围内的工具列表
        List<ToolConfig> domainTools = getToolsForDomain(domainCode);

        // 5. per-request 内置工具实例（通过 ToolProvider 统一注册，上下文通过构造器注入）
        InvocationParameters params = new InvocationParameters(
                sessionId, domainCode, userMessage, allDomains, eventSink);
        BuiltinTools builtinTools = new BuiltinTools(params, sessionDomainRepo, domainSwitchRepo, objectMapper, sessionQueueService);

        // 6. 构建每次请求独立的 DomainAssistant
        //    ⚠️ 每次请求重新 build 是有意为之：
        //    - systemMessageProvider 依赖 RAG 闭包（每次请求独立检索结果）
        //    - builtinTools 依赖 per-request 上下文（sessionId / eventSink 等）
        //    - toolProvider 依赖 domainCode 和 domainTools（不同域工具集不同）
        //
        //    ⚠️ 内置工具通过 ToolProvider 统一注册（而非 .tools() 静态注册），
        //    避免 LangChain4j 1.1.0 中 .tools() + .toolProvider() 混合使用时的
        //    executor 合并不一致导致 NPE（toolExecutor is null）。
        DomainAssistant assistant = AiServices.builder(DomainAssistant.class)
                .streamingChatModel(modelFactory.getStreamingChatModel())
                .systemMessageProvider(id -> systemPrompt)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                        .chatMemoryStore(memoryStore)
                        .build())
                .toolProvider(buildDynamicToolProvider(domainTools, eventSink, builtinTools))
                .build();

        // 7. 合并 AI 令牌流与工具事件流
        Flux<ChatEvent> tokenFlux = assistant.chat(sessionId, userMessage)
                .map(content -> ChatEvent.token(content, objectMapper))
                .doFinally(signal -> eventSink.tryEmitComplete());

        return Flux.merge(tokenFlux, eventSink.asFlux())
                .doOnError(e -> log.error("[DomainAgent] error sessionId={}", sessionId, e))
                .onErrorResume(e -> Flux.just(ChatEvent.error(e.getMessage(), objectMapper)));
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
    // ToolProvider 构造（仅动态工具：MCP + 域 HTTP）
    // -------------------------------------------------------

    /**
     * 构建动态 {@link ToolProvider}，每次请求调用前执行，返回三层工具：
     * <ol>
     *   <li>MCP 工具（最低优先级，由 {@link McpClientRegistry} 聚合）</li>
     *   <li>域 HTTP 工具（覆盖同名 MCP 工具）</li>
     *   <li>内置工具 {@code switch_domain} / {@code transfer_to_agent}（最高优先级，不可被覆盖）</li>
     * </ol>
     * <p>所有工具统一通过 {@link ToolProvider} 注册，避免 {@code .tools()} +
     * {@code .toolProvider()} 混合使用时 LangChain4j 内部 executor 合并缺失导致 NPE。
     */
    private ToolProvider buildDynamicToolProvider(List<ToolConfig> tools,
                                                   Sinks.Many<ChatEvent> eventSink,
                                                   BuiltinTools builtinTools) {
        return request -> {
            Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();

            // 第一层：MCP 工具（先写，优先级最低）
            // 每个 MCP executor 用事件包装器包裹，向前端发射 tool_call / tool_done SSE 事件
            try {
                ToolProviderResult mcpResult =
                        mcpClientRegistry.getToolProvider().provideTools(request);
                if (mcpResult != null && mcpResult.tools() != null) {
                    mcpResult.tools().forEach((spec, executor) ->
                            toolMap.put(spec, wrapWithEvents(spec.name(), executor, eventSink)));
                    log.debug("[DomainAgent] 已加载 MCP 工具数={}", mcpResult.tools().size());
                }
            } catch (Exception e) {
                log.warn("[DomainAgent] MCP 工具加载失败，已跳过", e);
            }

            // 第二层：域 HTTP 工具（覆盖同名 MCP 工具）
            for (ToolConfig tc : tools) {
                toolMap.put(buildToolSpec(tc), buildHttpToolExecutor(tc, eventSink));
            }

            // 第三层：内置工具（最高优先级，覆盖任何同名工具）
            toolMap.putAll(buildBuiltinToolSpecs(builtinTools));

            log.debug("[DomainAgent] 最终工具数={} MCP+域HTTP+内置", toolMap.size());
            return new ToolProviderResult(toolMap);
        };
    }

    /**
     * 将所有启用域拼装为 system prompt addon，告知 LLM 可切换的域列表。
     * 格式示例：「当前可用服务域：ecommerce（电商购物），logistics（物流配送）」
     */
    private String buildDomainAddon(List<DomainSummary> allDomains) {
        if (allDomains == null || allDomains.isEmpty()) {
            return null;
        }
        String domainList = allDomains.stream()
                .map(d -> d.code() + "（" + d.description() + "）")
                .collect(Collectors.joining("，"));
        return "当前可用服务域：" + domainList;
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
    /**
     * 用 SSE 事件包装器包裹 MCP {@link ToolExecutor}，执行前发射 {@code tool_call}，
     * 执行后发射 {@code tool_done}，与域 HTTP 工具保持一致的前端体验。
     */
    private ToolExecutor wrapWithEvents(String toolName,
                                        ToolExecutor delegate,
                                        Sinks.Many<ChatEvent> eventSink) {
        return (req, memId) -> {
            long start = System.currentTimeMillis();
            try {
                eventSink.tryEmitNext(ChatEvent.toolCall(
                        objectMapper.writeValueAsString(ToolCallPayload.running(toolName))));
            } catch (Exception ignored) { /* 序列化失败不阻断工具执行 */ }

            String result;
            boolean success = true;
            String errorMsg = null;
            try {
                result = delegate.execute(req, memId);
            } catch (Exception e) {
                success = false;
                errorMsg = e.getMessage();
                result = "工具执行失败: " + errorMsg;
                log.error("[DomainAgent] MCP tool error tool={}", toolName, e);
            }

            long durationMs = System.currentTimeMillis() - start;
            try {
                String doneJson = objectMapper.writeValueAsString(
                        success
                        ? ToolDonePayload.success(toolName, durationMs)
                        : ToolDonePayload.error(toolName, durationMs, errorMsg));
                eventSink.tryEmitNext(ChatEvent.toolDone(doneJson));
            } catch (Exception ignored) { /* 序列化失败不阻断结果返回 */ }

            return result;
        };
    }

    // -------------------------------------------------------
    // 工具规格（ToolSpecification）构造
    // -------------------------------------------------------

    /**
     * 根据 {@link ToolConfig#paramSchema()} 构建 LangChain4j {@link ToolSpecification}。
     *
     * <p>{@code paramSchema} 是标准 JSON Schema 字符串，格式示例：
     * <pre>{@code
     * {
     *   "properties": {
     *     "orderId":  { "type": "string",  "description": "订单号" },
     *     "quantity": { "type": "integer", "description": "数量" },
     *     "express":  { "type": "boolean", "description": "是否加急" }
     *   },
     *   "required": ["orderId"]
     * }
     * }</pre>
     *
     * <p>支持 type → JsonSchemaElement 映射：
     * string → {@link JsonStringSchema}，integer → {@link JsonIntegerSchema}，
     * number → {@link JsonNumberSchema}，boolean → {@link JsonBooleanSchema}，
     * array → {@link JsonArraySchema}（items 默认 string），
     * object / 未知 → {@link JsonStringSchema}（降级）。
     *
     * @param tc 工具配置
     * @return 完整的 ToolSpecification，包含 name、description 和 parameters
     */
    private ToolSpecification buildToolSpec(ToolConfig tc) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();

        if (StringUtils.isBlank(tc.paramSchema())) {
            return buildFinalSpec(tc, schemaBuilder);
        }

        try {
            Map<String, Object> schema = objectMapper.readValue(tc.paramSchema(), new TypeReference<>() {});

            // 解析 properties：逐个属性按 type 映射为对应的 JsonSchemaElement
            parseProperties(schema, schemaBuilder);

            // 解析 required 字段列表
            parseRequired(schema, schemaBuilder);

        } catch (Exception e) {
            log.error("[DomainAgent] paramSchema 解析失败，工具参数定义将为空 tool={}", tc.code(), e);
        }

        return buildFinalSpec(tc, schemaBuilder);
    }

    /**
     * 从 JSON Schema 的 "properties" 节点中提取每个属性的类型和描述，
     * 并添加到 {@link JsonObjectSchema.Builder} 中。
     *
     * <p>每个属性的 "type" 字段决定映射到哪种 {@link JsonSchemaElement}，
     * "description" 字段作为 LLM 的参数说明。缺失或非法 type 时降级为 string。
     */
    private void parseProperties(Map<String, Object> schema, JsonObjectSchema.Builder builder) {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());

        properties.forEach((name, def) -> {
            if (!(def instanceof Map<?, ?> propDef)) {
                // 非 Map 格式的 property 定义，降级为无描述的 string
                builder.addProperty(name, JsonStringSchema.builder().build());
                return;
            }

            String description = safeString(propDef.get("description"));
            String type = safeString(propDef.get("type"));
            JsonSchemaElement element = mapTypeToSchema(type, description);
            builder.addProperty(name, element);
        });
    }

    /**
     * 将 JSON Schema 的 type 字符串映射为 LangChain4j 的 {@link JsonSchemaElement}。
     *
     * @param type        JSON Schema type 值（string/integer/number/boolean/array/object）
     * @param description 属性描述文本
     * @return 对应的 JsonSchemaElement 实例
     */
    private JsonSchemaElement mapTypeToSchema(String type, String description) {
        return switch (type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number"  -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array"   -> JsonArraySchema.builder()
                    .description(description)
                    .items(JsonStringSchema.builder().build())
                    .build();
            // "string"、"object"、空值、未知类型均降级为 string
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    /**
     * 从 JSON Schema 根节点提取 "required" 数组并设置到 builder。
     */
    private void parseRequired(Map<String, Object> schema, JsonObjectSchema.Builder builder) {
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        if (required != null && !required.isEmpty()) {
            builder.required(required);
        }
    }

    /**
     * 安全地将 Object 转为 String，null 或非 String 类型返回空串。
     */
    private String safeString(Object value) {
        return value instanceof String s ? s : "";
    }

    /**
     * 使用 schemaBuilder 构建最终的 {@link ToolSpecification}。
     */
    private ToolSpecification buildFinalSpec(ToolConfig tc, JsonObjectSchema.Builder schemaBuilder) {
        return ToolSpecification.builder()
                .name(tc.code())
                .description(tc.description())
                .parameters(schemaBuilder.build())
                .build();
    }

    // -------------------------------------------------------
    // 内置工具规格（ToolSpecification + ToolExecutor）
    // 手动构建以避免 .tools() + .toolProvider() 合并 Bug
    // -------------------------------------------------------

    /**
     * 为 {@link BuiltinTools} 的两个方法手动构建 {@link ToolSpecification} + {@link ToolExecutor}，
     * 加入 ToolProvider 统一注册，避免 LangChain4j {@code .tools()} / {@code .toolProvider()} 混合使用
     * 时 executor 合并缺失导致 NPE。
     */
    private Map<ToolSpecification, ToolExecutor> buildBuiltinToolSpecs(BuiltinTools builtinTools) {
        Map<ToolSpecification, ToolExecutor> builtinMap = new LinkedHashMap<>();

        // ---- switch_domain ----
        ToolSpecification switchDomainSpec = ToolSpecification.builder()
                .name("switch_domain")
                .description("""
                        当用户问题与当前服务域无关、需要切换到其他服务域时调用。
                        【重要规则】：
                        1. 每次对话只调用一次，调用后立即停止所有其他工具调用；
                        2. 调用成功后不要再调用任何工具（包括天气、订单等查询工具）；
                        3. 切换后只需告知用户已切换域，并请用户在新对话中重新提问；
                        4. 如果工具返回了切换成功信号，说明切换已完成，不要重复调用。
                        可用域列表见系统提示。""")
                .parameters(JsonObjectSchema.builder()
                        .addProperty("targetDomainCode", JsonStringSchema.builder()
                                .description("目标域 code").build())
                        .addProperty("reason", JsonStringSchema.builder()
                                .description("切换原因（可选）").build())
                        .required(List.of("targetDomainCode"))
                        .build())
                .build();
        builtinMap.put(switchDomainSpec, (ToolExecutionRequest req, Object memId) -> {
            Map<String, Object> args = parseToolArgs(req.arguments());
            String target = stringArg(args, "targetDomainCode");
            String reason = stringArg(args, "reason");
            return builtinTools.switchDomain(target, reason);
        });

        // ---- transfer_to_agent ----
        ToolSpecification transferSpec = ToolSpecification.builder()
                .name("transfer_to_agent")
                .description("当用户明确要求转接人工客服时调用")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        builtinMap.put(transferSpec, (ToolExecutionRequest req, Object memId) ->
                builtinTools.transferToAgent());

        return builtinMap;
    }

    /**
     * 安全地将 ToolExecutionRequest 的 arguments JSON 解析为 Map。
     */
    private Map<String, Object> parseToolArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[DomainAgent] 无法解析工具参数: {}", arguments, e);
            return Map.of();
        }
    }

    /**
     * 从参数 Map 中安全提取字符串值。
     */
    private String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val instanceof String s ? s : null;
    }

    private Map<String, Object> parseArguments(String arguments) throws Exception {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(arguments, new TypeReference<>() {});
    }
}
