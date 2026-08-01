package com.aria.conversation.application.service.tool;

import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.application.service.cancellation.CancellationRegistry;
import com.aria.conversation.application.service.payload.ToolDonePayload;
import com.aria.conversation.infrastructure.ai.mcp.McpClientRegistry;
import com.aria.conversation.infrastructure.ai.tool.ToolSpecBuilder;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.pipeline.HttpToolRunner;
import com.aria.conversation.infrastructure.dit.pipeline.ToolCallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DomainToolProviderFactory 单元测试。
 *
 * <p>覆盖重点：工具执行前置取消检查（{@code preExecutionCheck} 内的 {@code isCancelled} 分支）。
 * 该分支在真实网络时序下窗口极窄（{@code takeUntilOther} 几乎立即截断 Reactor 流，
 * LLM 来不及再发起新工具调用），黑盒 E2E 测试无法稳定命中，故直接对 {@link ToolExecutor}
 * 做单元级验证，绕开时序竞争。
 *
 * <p>{@link Tracer#NOOP} 替代 mock，规避 {@code nextSpan().name().tag().start()} 链式调用的
 * 逐层 stub 麻烦。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DomainToolProviderFactory 工具执行取消/幂等检查")
class DomainToolProviderFactoryTest {

    @Mock private McpClientRegistry mcpClientRegistry;
    @Mock private HttpToolRunner httpToolRunner;
    @Mock private CancellationRegistry cancellationRegistry;

    private DomainToolProviderFactory factory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TURN_ID = "turn-abc-123";
    private static final String TOOL_CODE = "list_orders";

    @BeforeEach
    void setUp() {
        ToolSpecBuilder toolSpecBuilder = new ToolSpecBuilder(objectMapper);
        factory = new DomainToolProviderFactory(
                mcpClientRegistry, httpToolRunner, toolSpecBuilder,
                objectMapper, Tracer.NOOP, cancellationRegistry);
        // MCP 未配置任何服务端时的默认行为：返回空结果，不影响域工具测试
        when(mcpClientRegistry.getToolProvider()).thenReturn(
                request -> new ToolProviderResult(Map.of()));
    }

    private ToolConfig domainTool() {
        return new ToolConfig(
                TOOL_CODE, "查询订单", "查询用户订单列表", "HTTP", "GET",
                "https://api.example.com/orders", null, null, null,
                "$.data", "NONE", null, 5000, false);
    }

    /** 通过 build() 构造 ToolProvider，再从 provideTools() 结果里取出目标工具的 executor。 */
    private ToolExecutor resolveExecutor(String turnId, ToolConfig tool) {
        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
        BuiltinTools noOpBuiltinTools = noOpBuiltinTools();
        ToolProvider provider = factory.build(List.of(tool), eventSink, noOpBuiltinTools, turnId);
        ToolProviderResult result = provider.provideTools(mock(ToolProviderRequest.class));
        return result.tools().entrySet().stream()
                .filter(e -> e.getKey().name().equals(tool.code()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到工具: " + tool.code()));
    }

    /** 内置工具此测试不涉及业务逻辑，传入最简依赖即可（buildToolSpecs 不会被调用到会抛异常的路径）。 */
    private BuiltinTools noOpBuiltinTools() {
        return new BuiltinTools(
                new InvocationParameters("s1", "ecommerce", "msg", List.of(),
                        Sinks.many().unicast().onBackpressureBuffer()),
                null, null, objectMapper, null);
    }

    private ToolExecutionRequest req(String code, String args) {
        return ToolExecutionRequest.builder().id("call_1").name(code).arguments(args).build();
    }

    // ── 核心场景：取消检查 ──────────────────────────────────────────────

    @Test
    @DisplayName("isCancelled=true 时跳过执行，返回 [CANCELLED] 且不调用 HttpToolRunner")
    void execute_whenCancelled_skipsAndReturnsCancelledMarker() {
        when(cancellationRegistry.isCancelled(TURN_ID)).thenReturn(true);
        ToolExecutor executor = resolveExecutor(TURN_ID, domainTool());

        String result = executor.execute(req(TOOL_CODE, "{}"), "mem-1");

        assertThat(result).contains("[CANCELLED]");
        verify(httpToolRunner, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("isCancelled=true 时仍发射 tool_done(success=false, errorMsg=已取消) 供前端同步 UI")
    void execute_whenCancelled_emitsToolDoneWithCancelledError() {
        when(cancellationRegistry.isCancelled(TURN_ID)).thenReturn(true);
        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
        ToolProvider provider = factory.build(List.of(domainTool()), eventSink, noOpBuiltinTools(), TURN_ID);
        ToolExecutor executor = provider.provideTools(mock(ToolProviderRequest.class))
                .tools().values().iterator().next();

        executor.execute(req(TOOL_CODE, "{}"), "mem-1");
        eventSink.tryEmitComplete();

        StepVerifier.create(eventSink.asFlux())
                .assertNext(event -> {
                    assertThat(event.eventType()).isEqualTo(ChatEvent.EventType.TOOL_DONE);
                    try {
                        ToolDonePayload payload = objectMapper.readValue(event.data(), ToolDonePayload.class);
                        assertThat(payload.tool()).isEqualTo(TOOL_CODE);
                        assertThat(payload.status()).isEqualTo("ERROR");
                        assertThat(payload.errorMsg()).isEqualTo("已取消");
                    } catch (Exception e) {
                        throw new AssertionError("tool_done payload 解析失败", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("isCancelled=false 时正常执行 HttpToolRunner 并返回结果")
    void execute_whenNotCancelled_executesNormally() {
        when(cancellationRegistry.isCancelled(TURN_ID)).thenReturn(false);
        when(httpToolRunner.execute(any(), any(), any()))
                .thenReturn(ToolCallResult.success(TOOL_CODE, "{\"orders\":[]}", 200, 10L));
        ToolExecutor executor = resolveExecutor(TURN_ID, domainTool());

        String result = executor.execute(req(TOOL_CODE, "{}"), "mem-1");

        assertThat(result).isEqualTo("{\"orders\":[]}");
        verify(httpToolRunner, times(1)).execute(any(), any(), any());
    }

    // ── 幂等去重场景（同 turn 内相同工具+参数） ──────────────────────────

    @Test
    @DisplayName("同 turn 内相同工具+参数重复调用：第二次命中缓存，不重复调用 HttpToolRunner")
    void execute_duplicateCallInSameTurn_returnsFromCache() {
        when(cancellationRegistry.isCancelled(TURN_ID)).thenReturn(false);
        when(httpToolRunner.execute(any(), any(), any()))
                .thenReturn(ToolCallResult.success(TOOL_CODE, "{\"orders\":[1,2,3]}", 200, 10L));

        // 同一个 build() 调用内的两次执行，才共享同一个 turnToolCallCache（per-turn 生命周期）
        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
        ToolProvider provider = factory.build(List.of(domainTool()), eventSink, noOpBuiltinTools(), TURN_ID);
        ToolExecutor executor = provider.provideTools(mock(ToolProviderRequest.class))
                .tools().values().iterator().next();

        String first = executor.execute(req(TOOL_CODE, "{\"page\":1}"), "mem-1");
        String second = executor.execute(req(TOOL_CODE, "{\"page\":1}"), "mem-1");

        assertThat(first).isEqualTo(second).isEqualTo("{\"orders\":[1,2,3]}");
        // 幂等去重生效：HttpToolRunner 只被真正调用一次
        verify(httpToolRunner, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("同 turn 内不同参数调用：不触发缓存，各自正常执行")
    void execute_differentArgsInSameTurn_doesNotHitCache() {
        when(cancellationRegistry.isCancelled(TURN_ID)).thenReturn(false);
        when(httpToolRunner.execute(any(), any(), any()))
                .thenReturn(ToolCallResult.success(TOOL_CODE, "{\"orders\":[]}", 200, 10L));

        Sinks.Many<ChatEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
        ToolProvider provider = factory.build(List.of(domainTool()), eventSink, noOpBuiltinTools(), TURN_ID);
        ToolExecutor executor = provider.provideTools(mock(ToolProviderRequest.class))
                .tools().values().iterator().next();

        executor.execute(req(TOOL_CODE, "{\"page\":1}"), "mem-1");
        executor.execute(req(TOOL_CODE, "{\"page\":2}"), "mem-1");

        verify(httpToolRunner, times(2)).execute(any(), any(), any());
    }

    // ── turnId 隔离场景（I1 修复回归）────────────────────────────────────

    @Test
    @DisplayName("不同 turnId 独立判定取消状态：turnA 取消不影响 turnB 正常执行")
    void execute_differentTurnIds_cancelStateIsolated() {
        String turnA = "turn-A";
        String turnB = "turn-B";
        when(cancellationRegistry.isCancelled(turnA)).thenReturn(true);
        when(cancellationRegistry.isCancelled(turnB)).thenReturn(false);
        when(httpToolRunner.execute(any(), any(), any()))
                .thenReturn(ToolCallResult.success(TOOL_CODE, "{\"ok\":true}", 200, 10L));

        ToolExecutor executorA = resolveExecutor(turnA, domainTool());
        ToolExecutor executorB = resolveExecutor(turnB, domainTool());

        String resultA = executorA.execute(req(TOOL_CODE, "{}"), "mem-1");
        String resultB = executorB.execute(req(TOOL_CODE, "{}"), "mem-2");

        assertThat(resultA).contains("[CANCELLED]");
        assertThat(resultB).isEqualTo("{\"ok\":true}");
    }
}
