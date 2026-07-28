package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.DomainCodes;
import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.model.IntentType;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("LangChain4jIntentService 多意图分类")
class LangChain4jIntentServiceTest {

    @Mock private DynamicModelFactory modelFactory;
    @Mock private DomainRepository domainRepository;
    @Mock private RoutingConfigProvider routingConfigProvider;

    private LangChain4jIntentService service;

    private static IntentConfig intentConfig(String code, String desc) {
        return new IntentConfig(code, code, desc, List.of(), false, false, null,
                List.of(), List.of(), List.of(), List.of(), 0);
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        RoutingConfig config = new RoutingConfig();
        when(routingConfigProvider.getConfig()).thenReturn(config);
        service = new LangChain4jIntentService(
                modelFactory, domainRepository, new ObjectMapper(), routingConfigProvider);
    }

    @Test
    @DisplayName("classifyMulti: LLM 返回单意图 JSON 数组 → 正确解析")
    void classifyMulti_singleIntent_returnsCorrectResult() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds(
                "{\"intents\":[{\"intent\":\"FAQ_QUERY\",\"confidence\":0.9}]}");
        when(modelFactory.getChatModel()).thenReturn(mock);

        DomainConfig domain = new DomainConfig(
                DomainCodes.SYSTEM_DOMAIN, "系统域", null, null, null,
                List.of(intentConfig("FAQ_QUERY", "知识问答"),
                        intentConfig("TRANSFER_REQUEST", "转人工")));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.of(domain));

        List<IntentResult> results = service.classifyMulti("退款政策是什么？");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.FAQ_QUERY);
        assertThat(results.get(0).confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("classifyMulti: LLM 返回多意图 JSON 数组 → 全部解析")
    void classifyMulti_multipleIntents_returnsAll() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds(
                "{\"intents\":[" +
                "{\"intent\":\"COMPLAINT\",\"confidence\":0.95}," +
                "{\"intent\":\"FAQ_QUERY\",\"confidence\":0.82}]}");
        when(modelFactory.getChatModel()).thenReturn(mock);

        DomainConfig domain = new DomainConfig(
                DomainCodes.SYSTEM_DOMAIN, "系统域", null, null, null,
                List.of(intentConfig("COMPLAINT", "投诉"),
                        intentConfig("FAQ_QUERY", "知识问答")));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.of(domain));

        List<IntentResult> results = service.classifyMulti("我要投诉，顺便查一下物流");

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(IntentResult::intent))
                .containsExactlyInAnyOrder(IntentType.COMPLAINT, IntentType.FAQ_QUERY);
    }

    @Test
    @DisplayName("classifyMulti: __system__ 域不存在 → [UNKNOWN]，不抛异常")
    void classifyMulti_domainNotFound_returnsUnknown() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.empty());

        List<IntentResult> results = service.classifyMulti("任意消息");

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isEqualTo(IntentResult.UNKNOWN);
    }

    @Test
    @DisplayName("classifyMulti: LLM 返回未知意图 code → UNKNOWN（不静默映射为 FAQ_QUERY）")
    void classifyMulti_unknownIntentCode_returnsUnknown() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds(
                "{\"intents\":[{\"intent\":\"BANANA\",\"confidence\":0.8}]}");
        when(modelFactory.getChatModel()).thenReturn(mock);

        DomainConfig domain = new DomainConfig(
                DomainCodes.SYSTEM_DOMAIN, "系统域", null, null, null,
                List.of(intentConfig("FAQ_QUERY", "知识问答")));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.of(domain));

        List<IntentResult> results = service.classifyMulti("随便问个问题");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    @DisplayName("buildMultiPrompt: exampleQueries 注入静态示例")
    void buildMultiPrompt_injectsExamples() {
        IntentConfig intentWithExamples = new IntentConfig(
                "FAQ_QUERY", "FAQ_QUERY", "知识问答",
                List.of("退款政策是什么", "查物流", "商品质量问题"),
                false, false, null,
                List.of(), List.of(), List.of(), List.of(), 0);

        String prompt = service.buildMultiPrompt(List.of(intentWithExamples));

        assertThat(prompt).contains("退款政策是什么");
        assertThat(prompt).contains("查物流");
        assertThat(prompt).contains("intents");  // 多意图 JSON 格式标识
    }

    @Test
    @DisplayName("parseMultiResponse: 自定义业务 code → UNKNOWN，intentCode 保留原始值")
    void parseMultiResponse_customCode_intentCodePreserved() {
        List<IntentResult> results = service.parseMultiResponse(
                "{\"intents\":[{\"intent\":\"query_order\",\"confidence\":0.85}]}");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.UNKNOWN);
        assertThat(results.get(0).intentCode()).isEqualTo("query_order");
    }

    @Test
    @DisplayName("parseMultiResponse: 兼容旧格式单意图 JSON 兜底")
    void parseMultiResponse_legacySingleFormat_fallsBack() {
        // LLM 偶尔返回旧格式，应优雅兜底
        List<IntentResult> results = service.parseMultiResponse(
                "{\"intent\":\"FAQ_QUERY\",\"confidence\":0.9}");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).intent()).isEqualTo(IntentType.FAQ_QUERY);
    }
}
