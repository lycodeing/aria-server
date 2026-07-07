package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionChatMemoryStoreTest {

    @Mock private ConversationHistoryRepository historyRepo;
    private SessionChatMemoryStore store;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        store = new SessionChatMemoryStore(historyRepo);
    }

    @Test
    void getMessages_convertsToLangChain4jTypes() {
        when(historyRepo.findAll("s1")).thenReturn(List.of(
            ConversationMessage.of("user", "hello", 1L),
            ConversationMessage.of("assistant", "hi", 2L)
        ));
        var messages = store.getMessages("s1");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
    }

    @Test
    void updateMessages_callsSaveAll() {
        store.updateMessages("s1", List.of(UserMessage.from("hello"), AiMessage.from("hi")));
        verify(historyRepo).saveAll(eq("s1"), anyList());
    }

    @Test
    void deleteMessages_callsDelete() {
        store.deleteMessages("s1");
        verify(historyRepo).delete("s1");
    }

    /**
     * 回归用例：修复前，纯 tool_call 的 AiMessage（text=null）会被写成 content=null，
     * 触发 Redis 写入校验异常 "elements 不允许包含 null"。
     */
    @Test
    void updateMessages_toolCallOnlyAiMessage_preservesToolCallsAndNoNullContent() {
        when(historyRepo.findAll("s1")).thenReturn(List.of(
            ConversationMessage.of("user", "深圳最近天气怎么样", 1L)
        ));

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_abc")
                .name("get_weather_forecast")
                .arguments("{\"city\":\"深圳\"}")
                .build();
        AiMessage toolCallOnly = AiMessage.builder()
                .toolExecutionRequests(List.of(req))
                .build();

        store.updateMessages("s1", List.of(
                UserMessage.from("深圳最近天气怎么样"),
                toolCallOnly));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepo).saveAll(eq("s1"), captor.capture());
        List<ConversationMessage> saved = captor.getValue();

        assertThat(saved).hasSize(2);
        ConversationMessage aiMsg = saved.get(1);
        assertThat(aiMsg.role()).isEqualTo("assistant");
        // content 可以是 null（tool_call-only 场景）；关键是 toolCalls 必须完整
        assertThat(aiMsg.toolCalls()).hasSize(1);
        assertThat(aiMsg.toolCalls().get(0).id()).isEqualTo("call_abc");
        assertThat(aiMsg.toolCalls().get(0).name()).isEqualTo("get_weather_forecast");
        assertThat(aiMsg.toolCalls().get(0).arguments()).isEqualTo("{\"city\":\"深圳\"}");
    }

    @Test
    void updateMessages_toolExecutionResultMessage_carriesIdAndName() {
        when(historyRepo.findAll("s1")).thenReturn(List.of());

        ToolExecutionResultMessage tr = ToolExecutionResultMessage.from(
                "call_abc", "get_weather_forecast", "{\"tempC\":26}");
        store.updateMessages("s1", List.of(tr));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(historyRepo).saveAll(eq("s1"), captor.capture());
        List<ConversationMessage> saved = captor.getValue();

        assertThat(saved).hasSize(1);
        ConversationMessage tm = saved.get(0);
        assertThat(tm.role()).isEqualTo("tool");
        assertThat(tm.toolRequestId()).isEqualTo("call_abc");
        assertThat(tm.toolName()).isEqualTo("get_weather_forecast");
        assertThat(tm.content()).isEqualTo("{\"tempC\":26}");
    }

    /**
     * 反向重建：历史里保存的 assistant tool_calls 必须能还原成合法 AiMessage，
     * 与后续 ToolExecutionResultMessage 严格配对。
     */
    @Test
    void getMessages_rebuildsAiMessageWithToolCalls() {
        ConversationMessage assistantWithCall = new ConversationMessage(
                "assistant", null, 2L, null, null, null,
                List.of(new ConversationMessage.ToolCall(
                        "call_abc", "get_weather_forecast", "{\"city\":\"深圳\"}")));
        ConversationMessage toolResult = new ConversationMessage(
                "tool", "{\"tempC\":26}", 3L, null,
                "call_abc", "get_weather_forecast", null);
        when(historyRepo.findAll("s1")).thenReturn(List.of(assistantWithCall, toolResult));

        var messages = store.getMessages("s1");
        assertThat(messages).hasSize(2);
        AiMessage ai = (AiMessage) messages.get(0);
        assertThat(ai.hasToolExecutionRequests()).isTrue();
        assertThat(ai.toolExecutionRequests()).hasSize(1);
        assertThat(ai.toolExecutionRequests().get(0).id()).isEqualTo("call_abc");
        assertThat(messages.get(1)).isInstanceOf(ToolExecutionResultMessage.class);
    }
}
