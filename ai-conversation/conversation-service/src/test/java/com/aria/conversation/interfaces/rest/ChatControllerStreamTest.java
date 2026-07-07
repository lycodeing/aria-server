package com.aria.conversation.interfaces.rest;

import com.aria.conversation.application.service.ChatAppService;
import com.aria.conversation.application.service.ChatEvent;
import com.aria.conversation.application.service.payload.ErrorPayload;
import com.aria.conversation.application.service.payload.TokenPayload;
import com.aria.conversation.application.service.payload.ToolCallPayload;
import com.aria.conversation.application.service.payload.ToolDonePayload;
import com.aria.conversation.application.service.payload.TransferPayload;
import com.aria.conversation.application.service.support.SseJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ChatController SSE wire format 契约测试。
 *
 * <p>验证 Controller 层的 ChatEvent → ServerSentEvent 映射输出符合契约：
 * <ul>
 *   <li>token 事件：eventType == null，data 为 {@code {"content":"..."}} JSON 信封，
 *       且空白/换行/emoji 通过 JSON 序列化精确往返</li>
 *   <li>结构化事件（transfer / tool_call / tool_done / error）：eventType 为对应字符串，
 *       data 为对应 payload 的 JSON</li>
 *   <li>流末尾必然追加 event:done + data:[DONE] 终止帧</li>
 *   <li>非法 sessionId 直接返回 error 事件（JSON 信封）+ done 终止帧，不调用应用层</li>
 * </ul>
 *
 * <p>本测试直接对 Controller 实例操作，避免启动 WebFlux slice。
 * 应用层 {@link ChatAppService#stream} 被 mock，测试聚焦于 wire format 与终止帧行为，
 * 而非业务路由（业务路由由 {@link com.aria.conversation.application.service.ChatAppServiceIntentTest} 覆盖）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController SSE wire format 契约")
class ChatControllerStreamTest {

    @Mock private ChatAppService chatService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(chatService, objectMapper);
    }

    // -------------------------------------------------------
    // Token wire format：JSON 信封精确保留空白/换行/emoji
    // -------------------------------------------------------

    @Test
    @DisplayName("token 事件的 data 是合法 JSON，content 字段精确保留前导空格与换行")
    void tokenEvent_dataIsJsonEnvelopeAndPreservesWhitespace() {
        when(chatService.stream(anyString(), anyString(), any()))
                .thenReturn(Flux.just(
                        ChatEvent.token("### ", objectMapper),
                        ChatEvent.token(" 🔴 ", objectMapper),
                        ChatEvent.token("实时天气", objectMapper),
                        ChatEvent.token("\n\n", objectMapper)));

        Flux<ServerSentEvent<String>> stream = controller.streamChat(request("s1", "查天气"));

        StepVerifier.create(stream)
                .assertNext(sse -> {
                    // token 事件：无 event 字段（对应 SSE 默认事件）
                    assertThat(sse.event()).isNull();
                    // data 是 {"content":"### "} JSON 信封
                    assertThat(readTokenContent(sse.data())).isEqualTo("### ");
                })
                .assertNext(sse -> assertThat(readTokenContent(sse.data())).isEqualTo(" 🔴 "))
                .assertNext(sse -> assertThat(readTokenContent(sse.data())).isEqualTo("实时天气"))
                .assertNext(sse -> assertThat(readTokenContent(sse.data())).isEqualTo("\n\n"))
                // 流末尾必然追加 done 终止帧
                .assertNext(this::assertDoneFrame)
                .verifyComplete();
    }

    // -------------------------------------------------------
    // 结构化事件：透传 event type + JSON payload
    // -------------------------------------------------------

    @Test
    @DisplayName("transfer 事件：event=transfer，data 为 TransferPayload JSON")
    void transferEvent_hasTypeAndJsonPayload() {
        String payload = SseJson.encode(objectMapper,
                new TransferPayload("agent_transfer", "已为您转接人工客服"));
        when(chatService.stream(anyString(), anyString(), any()))
                .thenReturn(Flux.just(ChatEvent.transfer(payload)));

        Flux<ServerSentEvent<String>> stream = controller.streamChat(request("s2", "转人工"));

        StepVerifier.create(stream)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo(ChatEvent.EventType.TRANSFER);
                    try {
                        TransferPayload parsed = objectMapper.readValue(sse.data(), TransferPayload.class);
                        assertThat(parsed.intentCode()).isEqualTo("agent_transfer");
                        assertThat(parsed.message()).isEqualTo("已为您转接人工客服");
                    } catch (Exception e) {
                        throw new AssertionError("transfer payload 解析失败", e);
                    }
                })
                .assertNext(this::assertDoneFrame)
                .verifyComplete();
    }

    @Test
    @DisplayName("tool_call / tool_done 事件：event 类型与 payload 字段完整透传")
    void toolLifecycleEvents_areFullyPassedThrough() {
        String callJson = SseJson.encode(objectMapper, ToolCallPayload.running("get_weather"));
        String doneJson = SseJson.encode(objectMapper,
                new ToolDonePayload("get_weather", "SUCCESS", 1499L, null));

        when(chatService.stream(anyString(), anyString(), any()))
                .thenReturn(Flux.just(
                        ChatEvent.toolCall(callJson),
                        ChatEvent.toolDone(doneJson)));

        Flux<ServerSentEvent<String>> stream = controller.streamChat(request("s3", "查询天气"));

        StepVerifier.create(stream)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo(ChatEvent.EventType.TOOL_CALL);
                    try {
                        ToolCallPayload p = objectMapper.readValue(sse.data(), ToolCallPayload.class);
                        assertThat(p.tool()).isEqualTo("get_weather");
                        assertThat(p.status()).isEqualTo("RUNNING");
                    } catch (Exception e) {
                        throw new AssertionError("tool_call 解析失败", e);
                    }
                })
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo(ChatEvent.EventType.TOOL_DONE);
                    try {
                        ToolDonePayload p = objectMapper.readValue(sse.data(), ToolDonePayload.class);
                        assertThat(p.tool()).isEqualTo("get_weather");
                        assertThat(p.status()).isEqualTo("SUCCESS");
                        assertThat(p.durationMs()).isEqualTo(1499L);
                    } catch (Exception e) {
                        throw new AssertionError("tool_done 解析失败", e);
                    }
                })
                .assertNext(this::assertDoneFrame)
                .verifyComplete();
    }

    @Test
    @DisplayName("error 事件：event=error，data 为 ErrorPayload JSON（不是裸字符串）")
    void errorEvent_isJsonEnvelope() {
        when(chatService.stream(anyString(), anyString(), any()))
                .thenReturn(Flux.just(ChatEvent.error("上游服务异常", objectMapper)));

        Flux<ServerSentEvent<String>> stream = controller.streamChat(request("s4", "hi"));

        StepVerifier.create(stream)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo(ChatEvent.EventType.ERROR);
                    try {
                        ErrorPayload p = objectMapper.readValue(sse.data(), ErrorPayload.class);
                        assertThat(p.message()).isEqualTo("上游服务异常");
                    } catch (Exception e) {
                        throw new AssertionError("error payload 解析失败", e);
                    }
                })
                .assertNext(this::assertDoneFrame)
                .verifyComplete();
    }

    // -------------------------------------------------------
    // 非法请求：Controller 层短路
    // -------------------------------------------------------

    @Test
    @DisplayName("非法 sessionId：直接返回 error 事件（JSON 信封）+ done 终止帧，不调用应用层")
    void invalidSessionId_shortCircuitsWithErrorEnvelope() {
        Flux<ServerSentEvent<String>> stream =
                controller.streamChat(request("bad sid!", "问题"));

        StepVerifier.create(stream)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo(ChatEvent.EventType.ERROR);
                    try {
                        ErrorPayload p = objectMapper.readValue(sse.data(), ErrorPayload.class);
                        assertThat(p.message()).contains("非法的 sessionId");
                    } catch (Exception e) {
                        throw new AssertionError("error payload 解析失败", e);
                    }
                })
                .assertNext(this::assertDoneFrame)
                .verifyComplete();
    }

    @Test
    @DisplayName("空消息：直接返回 done 终止帧，不调用应用层")
    void blankMessage_returnsOnlyDoneFrame() {
        Flux<ServerSentEvent<String>> stream = controller.streamChat(request("s5", "   "));

        StepVerifier.create(stream)
                .assertNext(this::assertDoneFrame)
                .verifyComplete();
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private ChatController.ChatRequest request(String sessionId, String message) {
        ChatController.ChatRequest req = new ChatController.ChatRequest();
        req.setSessionId(sessionId);
        req.setMessage(message);
        return req;
    }

    private String readTokenContent(String jsonEnvelope) {
        try {
            return objectMapper.readValue(jsonEnvelope, TokenPayload.class).content();
        } catch (Exception e) {
            throw new AssertionError("token JSON 信封解析失败: " + jsonEnvelope, e);
        }
    }

    /**
     * 断言当前 SSE 帧为流终止帧：{@code event:done, data:[DONE]}。
     * 与 OpenAI 规范一致，data 保持字面量而非 JSON 信封。
     */
    private void assertDoneFrame(ServerSentEvent<String> sse) {
        assertThat(sse.event()).isEqualTo(ChatEvent.EventType.DONE);
        assertThat(sse.data()).isEqualTo("[DONE]");
    }
}
