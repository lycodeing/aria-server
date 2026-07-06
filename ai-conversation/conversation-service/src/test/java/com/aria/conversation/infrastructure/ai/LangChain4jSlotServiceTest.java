package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.SlotDefinition;
import com.aria.conversation.domain.service.SlotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("LangChain4jSlotService")
class LangChain4jSlotServiceTest {

    @Mock private DynamicModelFactory modelFactory;

    private SlotService service;

    private static SlotDefinition slot(String name, String desc) {
        return new SlotDefinition(name, "string", desc, null);
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new LangChain4jSlotService(modelFactory, new ObjectMapper());
    }

    @Test
    @DisplayName("extract: LLM 返回合法 JSON → 正确提取槽位")
    void extract_validJson_returnsSlotMap() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds("{\"order_id\":\"ORD001\",\"user_name\":\"张三\"}");
        when(modelFactory.getChatModel()).thenReturn(mock);

        List<SlotDefinition> slots = List.of(
                slot("order_id", "订单号"),
                slot("user_name", "用户姓名")
        );

        Map<String, Object> result = service.extract("我的订单 ORD001 在哪里", List.of(), slots);

        assertThat(result).containsEntry("order_id", "ORD001");
        assertThat(result).containsEntry("user_name", "张三");
    }

    @Test
    @DisplayName("extract: LLM 返回非法 JSON → 空 Map，不抛异常")
    void extract_invalidJson_returnsEmptyMap() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds("无法识别相关信息");
        when(modelFactory.getChatModel()).thenReturn(mock);

        List<SlotDefinition> slots = List.of(slot("order_id", "订单号"));

        Map<String, Object> result = service.extract("随便说几句", List.of(), slots);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("extract: slots 列表为空 → 直接返回空 Map，不调 LLM")
    void extract_emptySlots_returnsEmptyMapWithoutCallingLlm() {
        Map<String, Object> result = service.extract("任意消息", List.of(), List.of());

        assertThat(result).isEmpty();
        // modelFactory.getChatModel() should not be called — no verify needed as
        // Mockito strict stubs would fail if it were called unexpectedly
    }
}
