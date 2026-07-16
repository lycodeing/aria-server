package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.canned.CannedResponseDO;
import com.aria.conversation.infrastructure.canned.CannedResponseGroupDO;
import com.aria.conversation.infrastructure.canned.CannedResponseGroupMapper;
import com.aria.conversation.infrastructure.canned.CannedResponseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CannedResponseAppServiceTest {

    @Mock CannedResponseGroupMapper groupMapper;
    @Mock CannedResponseMapper cannedMapper;
    @InjectMocks CannedResponseAppService service;

    @Test
    void search_withBlankQuery_returnsMostUsed() {
        // given: 空关键词时走 fallback（不调用 searchByKeyword）
        CannedResponseDO cr = new CannedResponseDO();
        cr.setId(1L); cr.setTitle("常用语"); cr.setScope("PUBLIC"); cr.setUseCount(100);
        when(cannedMapper.selectList(any())).thenReturn(List.of(cr));
        // when
        List<CannedResponseDO> result = service.search("  ", 99L, null, 10);
        // then
        assertThat(result).hasSize(1);
        verify(cannedMapper, never()).searchByKeyword(any(), any(), any(), anyInt());
    }

    @Test
    void search_withKeyword_delegatesToFullTextSearch() {
        CannedResponseDO cr = new CannedResponseDO();
        cr.setId(2L); cr.setTitle("退款流程"); cr.setScope("PUBLIC");
        when(cannedMapper.searchByKeyword("退款", 99L, null, 10)).thenReturn(List.of(cr));
        List<CannedResponseDO> result = service.search("退款", 99L, null, 10);
        assertThat(result).hasSize(1);
        verify(cannedMapper).searchByKeyword("退款", 99L, null, 10);
    }

    @Test
    void deleteGroup_withChildren_throwsBusinessException() {
        CannedResponseGroupDO group = new CannedResponseGroupDO();
        group.setId(1L); group.setDeleted(false);
        when(groupMapper.selectById(1L)).thenReturn(group);
        // 子分组存在
        when(groupMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.deleteGroup(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("子分组");
    }

    @Test
    void updatePrivate_byNonOwner_throwsBusinessException() {
        CannedResponseDO cr = new CannedResponseDO();
        cr.setId(5L); cr.setScope("PRIVATE"); cr.setOwnerId(100L); cr.setDeleted(false);
        when(cannedMapper.selectById(5L)).thenReturn(cr);
        // agentId=999 不是 owner
        assertThatThrownBy(() -> service.updatePrivate(5L, "new title", "new content", 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("权限");
    }
}
