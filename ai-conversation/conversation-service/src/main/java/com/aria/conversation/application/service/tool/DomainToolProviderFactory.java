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
 * <ol>
 *   <li>低优先级：MCP 工具（外部服务动态工具），失败时 rethrow</li>
 *   <li>中优先级：域 HTTP 工具（覆盖同名 MCP 工具），失败时返回错误字符串</li>
 *   <li>高优先级：内置工具 switch_domain / transfer_to_agent（不可被覆盖）</li>
 * </ol>
 *
 * <p>所有工具执行均通过 {@link #buildTracedExecutor} 统一包裹 Micrometer Span + SSE 事件，
 * 消除 HTTP/MCP 两条路径的重复代码。
 *
 * <p><b>per-request 原则：</b>{@link #build} 必须每次请求重新调用，不可复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainToolProviderFactory {

    private final McpClientRegistry mcpClientRegistry;
    private final HttpToolRunner httpToolRunner;
    private final ToolSpecBuilder toolSpecBuilder;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

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

    // ── 工具加载 ────────────────────────────────────────────────────

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
        tools.forEach(tc -> toolMap.put(toolSpecBuilder.build(tc), buildHttpExecutor(tc, eventSink)));
    }

    // ── 工具执行器构建 ───────────────────────────────────────────────

    /**
     * 构建域 HTTP 工具执行器。
     * 业务失败（isSuccess=false）和系统异常均转为错误字符串返回（不 rethrow）。
     */
    private ToolExecutor buildHttpExecutor(ToolConfig tc, Sinks.Many<ChatEvent> eventSink) {
        return buildTracedExecutor(tc.code(), "http", false, eventSink, (req, memId) -> {
            Map<String, Object> args = parseArgs(req.arguments());
            ToolCallResult result = httpToolRunner.execute(tc, args, Map.of());
            if (!result.isSuccess()) {
                throw new RuntimeException(result.getErrorMsg());
            }
            return result.getResponse();
        });
    }

    /**
     * 为 MCP ToolExecutor 包裹 Span + SSE 事件。异常向上 rethrow，由 LangChain4j 处理。
     */
    private ToolExecutor wrapWithSseEvents(String name, ToolExecutor delegate,
                                           Sinks.Many<ChatEvent> eventSink) {
        return buildTracedExecutor(name, "mcp", true, eventSink, delegate::execute);
    }

    // ── 公共追踪骨架 ─────────────────────────────────────────────────

    /**
     * 统一的 Span + SSE 事件包裹骨架，消除 HTTP/MCP 两个执行器中的重复代码。
     *
     * <p>执行流程：发射 tool_call → 创建 Span → 执行 action → 发射 tool_done → 结束 Span。
     *
     * @param name           工具名称（用于 Span 名称和 SSE 事件）
     * @param type           工具类型标签（"http"/"mcp"），写入 Span tag
     * @param rethrowOnError true=异常 rethrow（MCP）；false=返回错误字符串（HTTP）
     * @param sink           SSE 事件发射器
     * @param action         实际工具执行逻辑
     */
    private ToolExecutor buildTracedExecutor(String name, String type, boolean rethrowOnError,
                                              Sinks.Many<ChatEvent> sink, TracedToolAction action) {
        return (req, memId) -> {
            long start = System.currentTimeMillis();
            emitToolCall(name, sink);
            var span = tracer.nextSpan().name("tool." + name).tag("tool.type", type).start();
            try (var ignored = tracer.withSpan(span)) {
                String result = action.execute(req, memId);
                span.tag("tool.success", "true");
                emitToolDone(name, true, null, elapsed(start), sink);
                return result;
            } catch (Exception e) {
                span.tag("tool.success", "false").error(e);
                emitToolDone(name, false, e.getMessage(), elapsed(start), sink);
                if (rethrowOnError) {
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException(e);
                }
                log.error("[ToolFactory] 工具执行异常 tool={}", name, e);
                return "工具执行失败: " + e.getMessage();
            } finally {
                span.end();
            }
        };
    }

    /** 工具执行动作函数接口（允许抛受检异常，由 buildTracedExecutor 统一处理）。 */
    @FunctionalInterface
    private interface TracedToolAction {
        String execute(ToolExecutionRequest req, Object memId) throws Exception;
    }

    // ── SSE 事件 ─────────────────────────────────────────────────────

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

    // ── 工具方法 ─────────────────────────────────────────────────────

    private static long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
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
