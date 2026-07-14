package com.aria.conversation.application.service.tool;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.application.service.payload.ToolCallPayload;
import com.aria.conversation.application.service.payload.ToolDonePayload;
import com.aria.conversation.infrastructure.ai.mcp.McpClientRegistry;
import com.aria.conversation.infrastructure.ai.tool.ToolSpecBuilder;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.pipeline.HttpToolRunner;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 域工具提供者工厂。
 *
 * <p>按优先级组装三层 ToolProvider：
 * <ul>
 *   <li>低优先级：MCP 工具（外部服务动态工具），失败时跳过</li>
 *   <li>中优先级：域 HTTP 工具（覆盖同名 MCP 工具）</li>
 *   <li>高优先级：内置工具 switch_domain / transfer_to_agent（不可被覆盖）</li>
 * </ul>
 *
 * <p>所有工具统一通过 ToolProvider 注册，避免 {@code .tools()} + {@code .toolProvider()}
 * 混合使用时 LangChain4j 1.1.0 executor 合并缺失导致 NPE。
 *
 * <p><b>per-request 原则：</b>每次请求必须调用 {@link #build} 重新构建 ToolProvider，
 * 不可复用，因为 builtinTools 和 eventSink 均携带请求级上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainToolProviderFactory {

    /** MCP 工具注册表，聚合所有外部 MCP 服务端提供的动态工具 */
    private final McpClientRegistry mcpClientRegistry;
    /** 域 HTTP 工具执行器，负责模板渲染、HTTP 调用和结果提取 */
    private final HttpToolRunner    httpToolRunner;
    /** 工具规格构造器，将 ToolConfig.paramSchema JSON Schema 转换为 LangChain4j ToolSpecification */
    private final ToolSpecBuilder   toolSpecBuilder;
    /** JSON 序列化工具，用于构造 tool_call / tool_done SSE 事件载荷 */
    private final ObjectMapper      objectMapper;

    /**
     * 构建当前请求独立的 ToolProvider，供 DomainAgentService 挂载到 AiServices。
     *
     * @param domainTools  当前域的 HTTP 工具配置列表，来自 DomainConfig
     * @param eventSink    SSE 事件发射器，工具执行前后向前端推送 tool_call / tool_done 事件
     * @param builtinTools 内置工具实例（per-request），含 sessionId / domainCode 等会话上下文
     * @return 组装完成的三层 ToolProvider
     */
    public ToolProvider build(List<ToolConfig> domainTools,
                              Sinks.Many<ChatEvent> eventSink,
                              BuiltinTools builtinTools) {
        return request -> {
            Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();
            loadMcpTools(toolMap, eventSink);
            loadDomainTools(toolMap, domainTools, eventSink);
            toolMap.putAll(builtinTools.buildToolSpecs());
            log.debug("[ToolFactory] 工具总数={}", toolMap.size());
            return new ToolProviderResult(toolMap);
        };
    }

    /**
     * 加载 MCP 工具并用 SSE 事件包装器包裹，加载失败时跳过不影响域工具和内置工具。
     */
    private void loadMcpTools(Map<ToolSpecification, ToolExecutor> toolMap,
                               Sinks.Many<ChatEvent> eventSink) {
        try {
            ToolProviderResult mcp = mcpClientRegistry.getToolProvider().provideTools(null);
            if (mcp != null && mcp.tools() != null) {
                mcp.tools().forEach((spec, exec) ->
                        toolMap.put(spec, wrapWithSseEvents(spec.name(), exec, eventSink)));
                log.debug("[ToolFactory] MCP 工具数={}", mcp.tools().size());
            }
        } catch (Exception e) {
            log.warn("[ToolFactory] MCP 工具加载失败，已跳过", e);
        }
    }

    /**
     * 加载当前域的 HTTP 工具，覆盖同名 MCP 工具（中优先级）。
     */
    private void loadDomainTools(Map<ToolSpecification, ToolExecutor> toolMap,
                                  List<ToolConfig> tools,
                                  Sinks.Many<ChatEvent> eventSink) {
        for (ToolConfig tc : tools) {
            toolMap.put(toolSpecBuilder.build(tc), buildHttpExecutor(tc, eventSink));
        }
    }

    /**
     * 构建域 HTTP 工具的执行器。执行前发射 tool_call，执行后发射 tool_done。
     * 工具执行失败时返回错误描述字符串，不抛出异常，由 LLM 自行决策是否重试。
     */
    private ToolExecutor buildHttpExecutor(ToolConfig tc, Sinks.Many<ChatEvent> eventSink) {
        return (ToolExecutionRequest req, Object memId) -> {
            emitToolCall(tc.code(), eventSink);
            try {
                Map<String, Object> args = parseArgs(req.arguments());
                ToolCallResult result = httpToolRunner.execute(tc, args, Map.of());
                emitToolDone(tc.code(), result.isSuccess(), result.getErrorMsg(), eventSink);
                return result.isSuccess() ? result.getResponse() : "工具执行失败: " + result.getErrorMsg();
            } catch (Exception e) {
                log.error("[ToolFactory] HTTP 工具执行异常 tool={}", tc.code(), e);
                emitToolDone(tc.code(), false, e.getMessage(), eventSink);
                return "工具执行失败: " + e.getMessage();
            }
        };
    }

    /**
     * 用 SSE 事件包装器包裹 MCP ToolExecutor。
     * 执行失败时先发射 tool_done（失败状态）再重新抛出，确保前端不停在 loading 状态。
     */
    private ToolExecutor wrapWithSseEvents(String name, ToolExecutor delegate,
                                            Sinks.Many<ChatEvent> eventSink) {
        return (req, memId) -> {
            emitToolCall(name, eventSink);
            try {
                String result = delegate.execute(req, memId);
                emitToolDone(name, true, null, eventSink);
                return result;
            } catch (Exception e) {
                emitToolDone(name, false, e.getMessage(), eventSink);
                throw e;
            }
        };
    }

    private void emitToolCall(String toolCode, Sinks.Many<ChatEvent> sink) {
        try {
            sink.tryEmitNext(ChatEvent.toolCall(
                    objectMapper.writeValueAsString(ToolCallPayload.running(toolCode))));
        } catch (Exception e) {
            log.warn("[ToolFactory] tool_call 事件发射失败 tool={}", toolCode, e);
        }
    }

    private void emitToolDone(String toolCode, boolean success, String errorMsg,
                               Sinks.Many<ChatEvent> sink) {
        try {
            String json = success
                    ? objectMapper.writeValueAsString(ToolDonePayload.success(toolCode, 0))
                    : objectMapper.writeValueAsString(ToolDonePayload.error(toolCode, 0, errorMsg));
            sink.tryEmitNext(ChatEvent.toolDone(json));
        } catch (Exception e) {
            log.warn("[ToolFactory] tool_done 事件发射失败 tool={}", toolCode, e);
        }
    }

    private Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[ToolFactory] 工具参数解析失败: {}", arguments, e);
            return Map.of();
        }
    }
}
