package com.aria.conversation.infrastructure.dit.repository;

import com.aria.conversation.infrastructure.dit.domain.SessionDomainSwitchDO;
import com.aria.conversation.infrastructure.dit.domain.SwitchType;
import com.aria.conversation.infrastructure.dit.mapper.SessionDomainSwitchMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionDomainSwitchRepository")
class SessionDomainSwitchRepositoryTest {

    @Mock
    private SessionDomainSwitchMapper switchMapper;

    @InjectMocks
    private SessionDomainSwitchRepository repository;

    @Test
    @DisplayName("record: 正确插入切换记录各字段")
    void record_insertsCorrectFields() {
        ArgumentCaptor<SessionDomainSwitchDO> captor =
                ArgumentCaptor.forClass(SessionDomainSwitchDO.class);

        repository.record("session-001", "ecommerce", "finance",
                SwitchType.ROUTER_MODEL, "我要查基金", "小模型检测切换", 42L);

        verify(switchMapper).insert((SessionDomainSwitchDO) captor.capture());
        SessionDomainSwitchDO saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo("session-001");
        assertThat(saved.getFromDomain()).isEqualTo("ecommerce");
        assertThat(saved.getToDomain()).isEqualTo("finance");
        assertThat(saved.getSwitchType()).isEqualTo(SwitchType.ROUTER_MODEL);
        assertThat(saved.getTriggerMessage()).isEqualTo("我要查基金");
        assertThat(saved.getReason()).isEqualTo("小模型检测切换");
        assertThat(saved.getMsgSeq()).isEqualTo(42L);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("record: mapper 抛异常时不向上传播（写失败不中断主流程）")
    void record_mapperThrows_doesNotPropagate() {
        doThrow(new RuntimeException("DB down")).when(switchMapper).insert(any(SessionDomainSwitchDO.class));

        assertDoesNotThrow(() ->
                repository.record("session-002", null, "ecommerce",
                        SwitchType.INITIAL, "进入服务", "用户进入服务入口", null));
    }
}
