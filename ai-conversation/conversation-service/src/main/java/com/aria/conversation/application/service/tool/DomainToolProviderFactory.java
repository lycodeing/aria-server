package com.aria.conversation.application.service.tool;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.application.service.cancellation.CancellationRegistry;
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
import java.util.concurrent.ConcurrentHashMap;

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

    /** 工具被取消时返回给 LLM 的结果字符串（M7 修复：提为常量） */
    private static final String CANCELLED_RESULT =
            "[CANCELLED] 操作已取消，请告知用户操作已停止。";

    private final McpClientRegistry mcpClientRegistry;
    private final HttpToolRunner httpToolRunner;
    private final ToolSpecBuilder toolSpecBuilder;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    /** 取消信号注册表，供工具执行前检查取消标志 */
    private final CancellationRegistry cancellationRegistry;

    /**
     * 构建域工具提供者（per-request，不可复用）。
     *
     * @param domainTools  域 HTTP 工具配置列表
     * @param eventSink    SSE 事件 Sink
     * @param builtinTools 内置工具（switch_domain / transfer_to_agent）
     * @param turnId       当前轮次 ID（{@link CancellationRegistry#register} 返回），用于取消检查。
     *                     I1 修复：改用 turnId 而非 sessionId，避免同一 session 连续请求
     *                     互相覆盖取消状态（旧轮次未清理完成时，新轮次可能被误判为已取消）。
     */
    public ToolProvider build(List<ToolConfig> domainTools,
                              Sinks.Many<ChatEvent> eventSink,
                              BuiltinTools builtinTools,
                              String turnId) {
        return request -> {
            // per-turn 工具调用缓存：在 lambda 内部创建，生命周期 = 单次 LLM turn。
            // 使用 ConcurrentHashMap（LangChain4j 不保证同 turn 内串行调用工具）。
            Map<String, String> turnToolCallCache = new ConcurrentHashMap<>();

            Map<ToolSpecification, ToolExecutor> toolMap = new LinkedHashMap<>();
            loadMcpTools(toolMap, eventSink, request, turnId, turnToolCallCache);
            loadDomainTools(toolMap, domainTools, eventSink, turnId, turnToolCallCache);
            toolMap.putAll(builtinTools.buildToolSpecs());
            log.debug("[ToolFactory] 工具总数={}", toolMap.size());
            return new ToolProviderResult(toolMap);
        };
    }

    // ── 工具加载 ────────────────────────────────────────────────────

    private void loadMcpTools(Map<ToolSpecification, ToolExecutor> toolMap,
                              Sinks.Many<ChatEvent> eventSink,
                              ToolProviderRequest request,
                              String turnId,
                              Map<String, String> turnToolCallCache) {
        try {
            ToolProviderResult mcp = mcpClientRegistry.getToolProvider().provideTools(request);
            if (mcp != null && mcp.tools() != null) {
                mcp.tools().forEach((spec, exec) ->
                        toolMap.put(spec, wrapWithSseEvents(spec.name(), exec, eventSink,
                                turnId, turnToolCallCache)));
                log.debug("[ToolFactory] MCP 工具数={}", mcp.tools().size());
            }
        } catch (Exception e) {
            log.warn("[ToolFactory] MCP 工具加载失败，已跳过", e);
        }
    }

    private void loadDomainTools(Map<ToolSpecification, ToolExecutor> toolMap,
                                 List<ToolConfig> tools,
                                 Sinks.Many<ChatEvent> eventSink,
                                 String turnId,
                                 Map<String, String> turnToolCallCache) {
        tools.forEach(tc -> toolMap.put(toolSpecBuilder.build(tc),
                buildHttpExecutor(tc, eventSink, turnId, turnToolCallCache)));
    }

    // ── 工具执行器构建 ───────────────────────────────────────────────

    /**
     * 构建域 HTTP 工具执行器。
     * 业务失败（isSuccess=false）和系统异常均转为错误字符串返回（不 rethrow）。
     */
    private ToolExecutor buildHttpExecutor(ToolConfig tc, Sinks.Many<ChatEvent> eventSink,
                                           String turnId,
                                           Map<String, String> turnToolCallCache) {
        var ctx = new ToolExecCtx(tc.code(), "http", false, eventSink, turnId, turnToolCallCache);
        return buildTracedExecutor(ctx, (req, memId) -> {
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
                                           Sinks.Many<ChatEvent> eventSink,
                                           String turnId,
                                           Map<String, String> turnToolCallCache) {
        var ctx = new ToolExecCtx(name, "mcp", true, eventSink, turnId, turnToolCallCache);
        return buildTracedExecutor(ctx, (req, memId) -> delegate.execute(req, memId));
    }

    // ── 公共追踪骨架 ─────────────────────────────────────────────────

    /**
     * 统一的 Span + SSE 事件包裹骨架，消除 HTTP/MCP 两个执行器中的重复代码。
     *
     * <p>执行流程：
     * <ol>
     *   <li>前置取消检查（{@code isCancelled} → 跳过执行，返回 [CANCELLED]）</li>
     *   <li>前置幂等去重（同 turn 内相同工具+参数 → 返回缓存结果）</li>
     *   <li>发射 tool_call → 创建 Span → 执行 action → 发射 tool_done → 结束 Span</li>
     * </ol>
     *
     * @param ctx    工具执行上下文（名称、类型、Sink、sessionId、去重缓存等）
     * @param action 实际工具执行逻辑
     */
    private ToolExecutor buildTracedExecutor(ToolExecCtx ctx, TracedToolAction action) {
        return (req, memId) -> {
            // cacheKey 提前计算一次，避免 preExecutionCheck + executeWithSpan 各算一次
            String cacheKey = buildCacheKey(ctx.name(), req);
            // ① 前置检查：取消 + 幂等去重
            String preCheckResult = preExecutionCheck(ctx, cacheKey);
            if (preCheckResult != null) {
                return preCheckResult;
            }
            // ② Span 包裹执行（cacheKey 复用，不再重复计算）
            return executeWithSpan(ctx, cacheKey, req, memId, action);
        };
    }

    /**
     * 前置检查：取消 → 返回 [CANCELLED]；幂等命中 → 返回缓存结果。
     * 两项均未命中时返回 null，调用方继续执行。
     */
    private String preExecutionCheck(ToolExecCtx ctx, String cacheKey) {
        // I1 修复：取消检查按 turnId（非 sessionId）判定，避免同 session 连续请求互相污染
        if (cancellationRegistry.isCancelled(ctx.turnId())) {
            log.info("[ToolFactory] 工具跳过（已取消）tool={} type={} turnId={}",
                    ctx.name(), ctx.type(), ctx.turnId());
            emitToolDone(ctx.name(), false, "已取消", 0L, ctx.sink());
            return CANCELLED_RESULT;
        }
        String cached = ctx.turnToolCallCache().get(cacheKey);
        if (cached != null) {
            log.warn("[ToolFactory] 同 turn 重复工具调用，返回缓存 tool={} turnId={}",
                    ctx.name(), ctx.turnId());
            emitToolDone(ctx.name(), true, null, 0L, ctx.sink());
            return cached;
        }
        return null;
    }

    /**
     * Span 包裹执行：发射 tool_call → Span → 执行 action → tool_done → 缓存写回。
     */
    private String executeWithSpan(ToolExecCtx ctx, String cacheKey,
                                   ToolExecutionRequest req, Object memId,
                                   TracedToolAction action) {
        long start = System.currentTimeMillis();
        emitToolCall(ctx.name(), ctx.sink());
        var span = tracer.nextSpan().name("tool." + ctx.name()).tag("tool.type", ctx.type()).start();
        try (var ignored = tracer.withSpan(span)) {
            String result = action.execute(req, memId);
            span.tag("tool.success", "true");
            emitToolDone(ctx.name(), true, null, elapsed(start), ctx.sink());
            ctx.turnToolCallCache().put(cacheKey, result);
            return result;
        } catch (Exception e) {
            span.tag("tool.success", "false").error(e);
            emitToolDone(ctx.name(), false, e.getMessage(), elapsed(start), ctx.sink());
            if (ctx.rethrowOnError()) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            log.error("[ToolFactory] 工具执行异常 tool={}", ctx.name(), e);
            return "工具执行失败: " + e.getMessage();
        } finally {
            span.end();
        }
    }

    /**
     * 工具执行上下文（record 封装参数，符合阿里规范 ≤5 参数）。
     *
     * @param name            工具名称
     * @param type            工具类型标签（"http"/"mcp"）
     * @param rethrowOnError  true=异常 rethrow（MCP）；false=返回错误字符串（HTTP）
     * @param sink            SSE 事件发射器
     * @param turnId          当前轮次 ID（取消检查用，I1 修复：不再用 sessionId）
     * @param turnToolCallCache per-turn 去重缓存（build() 内保证非 null，I2 修复：移除判空死代码）
     */
    private record ToolExecCtx(
            String name, String type, boolean rethrowOnError,
            Sinks.Many<ChatEvent> sink, String turnId,
            Map<String, String> turnToolCallCache) {}

    /**
     * 构建 per-turn 幂等缓存 key。使用 {@code \u0001} 分隔符避免冒号碰撞。
     */
    private static String buildCacheKey(String name, ToolExecutionRequest req) {
        return name + "\u0001" + (req.arguments() != null ? req.arguments() : "");
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
