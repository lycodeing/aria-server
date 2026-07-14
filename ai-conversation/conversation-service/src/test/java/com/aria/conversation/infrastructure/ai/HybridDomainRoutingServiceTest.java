package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService.RouteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HybridDomainRoutingService")
class HybridDomainRoutingServiceTest {

    @Mock private KeywordRegexDomainMatcher ruleMatcher;
    @Mock private LangChain4jDomainRoutingService llmRouter;
    @Mock private RoutingConfigProvider routingConfigProvider;
    @InjectMocks private HybridDomainRoutingService service;

    @BeforeEach
    void setUp() {
        when(routingConfigProvider.isDomainRuleEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("Tier1 命中：返回规则结果，不调用 LLM")
    void route_tier1Hit_llmNotCalled() {
        when(ruleMatcher.matchDomain("我想买基金")).thenReturn(Optional.of("finance"));

        RouteResult result = service.route("我想买基金", "ecommerce", List.of());

        assertThat(result.suggestedDomain()).isEqualTo("finance");
        assertThat(result.shouldSwitch()).isTrue();
        verify(llmRouter, never()).route(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("Tier1 命中，目标域与当前域相同：shouldSwitch=false")
    void route_tier1Hit_sameAsCurrent_noSwitch() {
        when(ruleMatcher.matchDomain(anyString())).thenReturn(Optional.of("ecommerce"));

        RouteResult result = service.route("查订单", "ecommerce", List.of());

        assertThat(result.shouldSwitch()).isFalse();
        verify(llmRouter, never()).route(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("Tier1 未命中：调用 LLM 路由")
    void route_tier1Miss_llmCalled() {
        when(ruleMatcher.matchDomain(anyString())).thenReturn(Optional.empty());
        when(llmRouter.route(anyString(), anyString(), anyList()))
                .thenReturn(new RouteResult("finance", true));

        RouteResult result = service.route("任意消息", "ecommerce", List.of());

        assertThat(result.suggestedDomain()).isEqualTo("finance");
        verify(llmRouter).route(anyString(), anyString(), anyList());
    }
}
