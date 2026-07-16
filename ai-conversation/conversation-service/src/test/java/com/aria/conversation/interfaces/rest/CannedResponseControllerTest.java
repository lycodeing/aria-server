// test/.../interfaces/rest/CannedResponseControllerTest.java
package com.aria.conversation.interfaces.rest;

import com.aria.conversation.application.service.CannedResponseAppService;
import com.aria.conversation.infrastructure.canned.CannedResponseDO;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CannedResponseControllerTest {

    @Mock CannedResponseAppService service;
    @InjectMocks CannedResponseController controller;

    @Test
    void search_capsLimitAt30() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            when(service.search(any(), eq(1L), isNull(), eq(30)))
                    .thenReturn(List.of());
            // limit=999 应被截断为 30
            controller.search(null, null, 999);
            verify(service).search(any(), eq(1L), isNull(), eq(30));
        }
    }

    @Test
    void recordUse_delegatesToServiceAsync() {
        controller.recordUse(42L);
        verify(service).recordUse(42L);
    }

    @Test
    void deleteMine_passesAgentIdToService() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(55L);
            controller.deleteMine(7L);
            verify(service).deletePrivate(7L, 55L);
        }
    }
}
