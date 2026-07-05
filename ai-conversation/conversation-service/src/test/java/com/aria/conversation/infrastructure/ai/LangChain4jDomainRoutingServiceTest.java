package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService.RouteResult;
import com.aria.conversation.infrastructure.dit.config.DomainConfig;
import com.aria.conversation.infrastructure.dit.repository.DomainRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.mock.ChatModelMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DisplayName("LangChain4jDomainRoutingService")
class LangChain4jDomainRoutingServiceTest {

    @Mock private DynamicModelFactory modelFactory;
    @Mock private DomainRepository domainRepository;

    private LangChain4jDomainRoutingService service;

    private static DomainConfig domainConfig(String code, String description) {
        return new DomainConfig(code, code + "_name", description, null, null, List.of());
    }

    private static ConversationMessage msg(String role, String content) {
        return ConversationMessage.of(role, content, 1L);
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new LangChain4jDomainRoutingService(modelFactory, domainRepository);
    }

    @Test
    @DisplayName("route: 模型返回与当前域相同 → shouldSwitch=false")
    void route_sameDomain_noSwitch() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds("ecommerce");
        when(modelFactory.getRouterModel()).thenReturn(mock);
        when(domainRepository.findAllEnabled()).thenReturn(List.of(
                domainConfig("ecommerce", "电商服务"),
                domainConfig("finance", "金融服务")));

        RouteResult result = service.route("我要查订单", "ecommerce", List.of());

        assertThat(result.shouldSwitch()).isFalse();
        assertThat(result.suggestedDomain()).isEqualTo("ecommerce");
    }

    @Test
    @DisplayName("route: 模型返回不同域 → shouldSwitch=true，suggestedDomain 正确")
    void route_differentDomain_switchTrue() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds("finance");
        when(modelFactory.getRouterModel()).thenReturn(mock);
        when(domainRepository.findAllEnabled()).thenReturn(List.of(
                domainConfig("ecommerce", "电商服务"),
                domainConfig("finance", "金融服务")));

        RouteResult result = service.route("我想买基金", "ecommerce",
                List.of(msg("user", "你好"), msg("assistant", "您好，请问有什么可以帮您？")));

        assertThat(result.shouldSwitch()).isTrue();
        assertThat(result.suggestedDomain()).isEqualTo("finance");
    }

    @Test
    @DisplayName("route: 模型返回非法域 code → 降级保持当前域，shouldSwitch=false")
    void route_illegalCode_fallbackToCurrentDomain() {
        ChatModel mock = ChatModelMock.thatAlwaysResponds("invalid_domain_xyz");
        when(modelFactory.getRouterModel()).thenReturn(mock);
        when(domainRepository.findAllEnabled()).thenReturn(List.of(
                domainConfig("ecommerce", "电商服务"),
                domainConfig("finance", "金融服务")));

        RouteResult result = service.route("随便说点什么", "ecommerce", List.of());

        assertThat(result.shouldSwitch()).isFalse();
        assertThat(result.suggestedDomain()).isEqualTo("ecommerce");
    }

    @Test
    @DisplayName("route: 只有一个域时直接返回，不调用模型")
    void route_singleDomain_noSwitch() {
        when(domainRepository.findAllEnabled()).thenReturn(
                List.of(domainConfig("ecommerce", "电商服务")));

        RouteResult result = service.route("随便问个问题", "ecommerce", List.of());

        assertThat(result.shouldSwitch()).isFalse();
        assertThat(result.suggestedDomain()).isEqualTo("ecommerce");
    }
}
