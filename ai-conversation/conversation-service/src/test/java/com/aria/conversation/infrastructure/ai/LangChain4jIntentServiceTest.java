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

@DisplayName("LangChain4jIntentService")
class LangChain4jIntentServiceTest {

    @Mock private DynamicModelFactory modelFactory;
    @Mock private DomainRepository domainRepository;

    private LangChain4jIntentService service;

    private static IntentConfig intentConfig(String code, String desc) {
        return new IntentConfig(code, code, desc, null, false, false, null, List.of(), List.of());
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new LangChain4jIntentService(modelFactory, domainRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("classify: LLM 返回合法意图 JSON → 正确 IntentResult")
    void classify_validResponse_returnsCorrectResult() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds("{\"intent\":\"FAQ_QUERY\",\"confidence\":0.9}");
        when(modelFactory.getChatModel()).thenReturn(mock);

        DomainConfig domain = new DomainConfig(
                DomainCodes.SYSTEM_DOMAIN, "系统域", null, null,
                List.of(intentConfig("FAQ_QUERY", "用户咨询产品服务问题"),
                        intentConfig("TRANSFER_REQUEST", "用户要求转人工")));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.of(domain));

        IntentResult result = service.classify("退款政策是什么？");

        assertThat(result.intent()).isEqualTo(IntentType.FAQ_QUERY);
        assertThat(result.confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("classify: LLM 返回非法意图字符串 → UNKNOWN")
    void classify_invalidIntentString_returnsUnknown() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds("{\"intent\":\"BANANA\",\"confidence\":0.8}");
        when(modelFactory.getChatModel()).thenReturn(mock);

        DomainConfig domain = new DomainConfig(
                DomainCodes.SYSTEM_DOMAIN, "系统域", null, null,
                List.of(intentConfig("FAQ_QUERY", "知识问答")));
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.of(domain));

        IntentResult result = service.classify("随便问个问题");

        assertThat(result.intent()).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    @DisplayName("classify: __system__ 域不存在 → UNKNOWN，不抛异常")
    void classify_domainNotFound_returnsUnknown() {
        when(domainRepository.findByCode(DomainCodes.SYSTEM_DOMAIN)).thenReturn(Optional.empty());

        IntentResult result = service.classify("任意消息");

        assertThat(result).isEqualTo(IntentResult.UNKNOWN);
    }
}
