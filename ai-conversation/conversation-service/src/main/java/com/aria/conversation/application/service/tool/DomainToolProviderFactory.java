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
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.micrometer.tracing.Tracer;
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
 * <p>每次工具调用前后各创建一个独立 Span（{@code tool.<toolCode>}），
 * 使 Zipkin / Grafana Tempo 等后端可以看到每次 AI 工具调用的名称、耗时和成功/失败状态。
 *
 * <p><b>per-request 原则：</b>每次请求必须调用 {@link #build} 重新构建 ToolProvider，
 * 不可复用，因为 builtinTools 和 eventSink 均携带请求级上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainToolProviderFactory {

    /** MCP 工具注册表 */
    private final McpClientRegistry  mcpClientRegistry;
    /** 域 HTTP 工具执行器 */
    private final HttpToolRunner      httpToolRunner;
    /** 工具规格构造器 */
    private final ToolSpecBuilder     toolSpecBuilder;
    /** JSON 序列化工具 */
    private final ObjectMapper        objectMapper;
    /**
     * Micrometer Tracer，用于为每次工具调用创建独立 Span。
     * Spring Boot Actuator 自动装配（需要 micrometer-tracing-bridge-brave 在 classpath）。
     */
    private final Tracer              tracer;

    public ToolProvider build(List<ToolConfig> domainTools,
                              Sinks.Many<ChatEvent> eventSink,
                              BuiltinTools builtinTools) {
        return request -> {
            Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();
            loadMcpTools(toolMap, eventSink, request);
            loadDomainTools(toolMap, domainTools, eventSink);
            toolMap.putAll(builtinTools.buildToolSpecs());
            log.debug("[ToolFactory] 工具总数={}", toolMap.size());
            return new ToolProviderResult(toolMap);
        };
    }

    private void loadMcpTools(Map<ToolSpecification, ToolExecutor> toolMap,
                               Sinks.Many<ChatEvent> eventSink,
                               ToolProviderRequest request) {
        try {
            ToolProviderResult mcp = mcpClientRegistry.getToolProvider().provideTools(request);
            if (mcp != null && mcp.tools() != null) {
                mcp.tools().forEach((spec, exec) ->
                        toolMap.put(spec, wrapWithSseEvents(spec.name(), exec, eventSink)));
                log.debug("[ToolFactory] MCP 工具数={}", mcp.tools().size());
            }
        } catch (Exception e) {
            log.warn("[ToolFactory] MCP 工具加载失败，已跳过", e);
        }
    }

    private void loadDomainTools(Map<ToolSpecification, ToolExecutor> toolMap,
                                  List<ToolConfig> tools,
                                  Sinks.Many<ChatEvent> eventSink) {
        for (ToolConfig tc : tools) {
            toolMap.put(toolSpecBuilder.build(tc), buildHttpExecutor(tc, eventSink));
        }
    }

    /**
     * 构建域 HTTP 工具执行器，包含：
     * <ol>
     *   <li>Micrometer Span（{@code tool.<code>}），记录工具调用耗时和成功/失败</li>
     *   <li>SSE tool_call / tool_done 事件推送</li>
     * </ol>
     */
    private ToolExecutor buildHttpExecutor(ToolConfig tc, Sinks.Many<ChatEvent> eventSink) {
        return (ToolExecutionRequest req, Object memId) -> {
            long start = System.currentTimeMillis();
            emitToolCall(tc.code(), eventSink);
            // 为本次工具调用创建独立 Span，Zipkin 可见耗时和名称
            var span = tracer.nextSpan()
                    .name("tool." + tc.code())
                    .tag("tool.type", "http")
                    .start();
            try (var ignored = tracer.withSpan(span)) {
                Map<String, Object> args = parseArgs(req.arguments());
                ToolCallResult result = httpToolRunner.execute(tc, args, Map.of());
                long durationMs = System.currentTimeMillis() - start;
                if (result.isSuccess()) {
                    span.tag("tool.success", "true");
                    emitToolDone(tc.code(), true, null, durationMs, eventSink);
                    return result.getResponse();
                } else {
                    span.tag("tool.success", "false")
                        .tag("tool.error", String.valueOf(result.getErrorMsg()))
                        .error(new RuntimeException(result.getErrorMsg()));
                    emitToolDone(tc.code(), false, result.getErrorMsg(), durationMs, eventSink);
                    return "工具执行失败: " + result.getErrorMsg();
                }
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - start;
                log.error("[ToolFactory] HTTP 工具执行异常 tool={}", tc.code(), e);
                span.tag("tool.success", "false").error(e);
                emitToolDone(tc.code(), false, e.getMessage(), durationMs, eventSink);
                return "工具执行失败: " + e.getMessage();
            } finally {
                span.end();
            }
        };
    }

    /**
     * 用 Span + SSE 事件包装 MCP ToolExecutor。
     */
    private ToolExecutor wrapWithSseEvents(String name, ToolExecutor delegate,
                                            Sinks.Many<ChatEvent> eventSink) {
        return (req, memId) -> {
            long start = System.currentTimeMillis();
            emitToolCall(name, eventSink);
            var span = tracer.nextSpan()
                    .name("tool." + name)
                    .tag("tool.type", "mcp")
                    .start();
            try (var ignored = tracer.withSpan(span)) {
                String result = delegate.execute(req, memId);
                long durationMs = System.currentTimeMillis() - start;
                span.tag("tool.success", "true");
                emitToolDone(name, true, null, durationMs, eventSink);
                return result;
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - start;
                span.tag("tool.success", "false").error(e);
                emitToolDone(name, false, e.getMessage(), durationMs, eventSink);
                throw e;
            } finally {
                span.end();
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
                               long durationMs, Sinks.Many<ChatEvent> sink) {
        try {
            String json = success
                    ? objectMapper.writeValueAsString(ToolDonePayload.success(toolCode, durationMs))
                    : objectMapper.writeValueAsString(ToolDonePayload.error(toolCode, durationMs, errorMsg));
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
