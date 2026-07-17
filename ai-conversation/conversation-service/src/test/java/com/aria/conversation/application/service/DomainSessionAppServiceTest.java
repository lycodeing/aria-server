package com.aria.conversation.application.service;

import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.domain.service.DomainRoutingService;
import com.aria.conversation.domain.service.DomainRoutingService.RouteResult;
import com.aria.conversation.infrastructure.dit.domain.DomainSwitchRecord;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainRepository;
import com.aria.conversation.infrastructure.dit.repository.SessionDomainSwitchRepository;
import com.aria.conversation.infrastructure.repository.ConversationHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomainSessionAppService")
class DomainSessionAppServiceTest {

    @Mock private SessionDomainRepository        sessionDomainRepo;
    @Mock private SessionDomainSwitchRepository  domainSwitchRepo;
    @Mock private ConversationHistoryRepository  historyRepository;
    @Mock private DomainRoutingService           domainRoutingService;

    private DomainSessionAppService service;

    @BeforeEach
    void setUp() {
        service = new DomainSessionAppService(sessionDomainRepo,
                domainSwitchRepo, historyRepository, domainRoutingService);
    }

    @Test
    @DisplayName("首次进入：以 domainCode 初始化激活域，写 INITIAL 切换记录，调用域路由")
    void resolveActiveDomain_firstEntry_initsDomainAndRoutes() {
        when(sessionDomainRepo.find("s1")).thenReturn(Optional.empty());
        when(historyRepository.findAll("s1")).thenReturn(List.of());
        when(domainRoutingService.route(any(), any(), any()))
                .thenReturn(new RouteResult("ecommerce", false));

        String result = service.resolveActiveDomain("s1", "你好", "ecommerce");

        assertThat(result).isEqualTo("ecommerce");
        verify(sessionDomainRepo).save("s1", "ecommerce");
        ArgumentCaptor<DomainSwitchRecord> captor = ArgumentCaptor.forClass(DomainSwitchRecord.class);
        verify(domainSwitchRepo).record(captor.capture());
        assertThat(captor.getValue().switchType()).isEqualTo("INITIAL");
    }

    @Test
    @DisplayName("已有激活域：直接读取，不写初始化记录")
    void resolveActiveDomain_existingDomain_noInitWrite() {
        when(sessionDomainRepo.find("s2")).thenReturn(Optional.of("finance"));
        when(historyRepository.findAll("s2")).thenReturn(List.of());
        when(domainRoutingService.route(any(), any(), any()))
                .thenReturn(new RouteResult("finance", false));

        String result = service.resolveActiveDomain("s2", "msg", "ecommerce");

        assertThat(result).isEqualTo("finance");
        verify(sessionDomainRepo, never()).save(any(), any());
    }

    @Test
    @DisplayName("路由建议切换域：更新 Redis 激活域并写 ROUTER_MODEL 切换记录")
    void resolveActiveDomain_routerSuggestsSwitch_updatesDomain() {
        when(sessionDomainRepo.find("s3")).thenReturn(Optional.of("ecommerce"));
        when(historyRepository.findAll("s3")).thenReturn(List.of());
        when(domainRoutingService.route(any(), eq("ecommerce"), any()))
                .thenReturn(new RouteResult("finance", true));

        String result = service.resolveActiveDomain("s3", "买基金", "ecommerce");

        assertThat(result).isEqualTo("finance");
        verify(sessionDomainRepo).save("s3", "finance");
        ArgumentCaptor<DomainSwitchRecord> captor = ArgumentCaptor.forClass(DomainSwitchRecord.class);
        verify(domainSwitchRepo).record(captor.capture());
        assertThat(captor.getValue().switchType()).isEqualTo("ROUTER_MODEL");
    }

    @Test
    @DisplayName("路由异常：降级保持当前域，不抛出异常")
    void resolveActiveDomain_routerThrows_fallbackToCurrentDomain() {
        when(sessionDomainRepo.find("s4")).thenReturn(Optional.of("ecommerce"));
        when(historyRepository.findAll("s4")).thenReturn(List.of());
        when(domainRoutingService.route(any(), any(), any()))
                .thenThrow(new RuntimeException("路由服务不可用"));

        String result = service.resolveActiveDomain("s4", "msg", "ecommerce");

        assertThat(result).isEqualTo("ecommerce");
    }
}
