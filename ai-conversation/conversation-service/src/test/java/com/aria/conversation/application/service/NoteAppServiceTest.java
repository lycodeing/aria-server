package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.persistence.entity.ConversationNoteEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationNoteMapper;
import com.aria.conversation.interfaces.rest.vo.NoteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoteAppServiceTest {

    @Mock ConversationNoteMapper noteMapper;

    NoteAppService service;

    @BeforeEach
    void setUp() {
        service = new NoteAppService(noteMapper);
    }

    @Test
    @DisplayName("新增备注 -> 插入 DB 并返回 NoteVO")
    void addNote_success() {
        doAnswer(inv -> {
            ConversationNoteEntity e = inv.getArgument(0);
            e.setId(1L);
            e.setCreateTime(LocalDateTime.now());
            e.setUpdateTime(LocalDateTime.now());
            return 1;
        }).when(noteMapper).insert(any(ConversationNoteEntity.class));

        NoteVO result = service.addNote("sess-001", "agent-001", "重要客户");

        ArgumentCaptor<ConversationNoteEntity> captor =
                ArgumentCaptor.forClass(ConversationNoteEntity.class);
        verify(noteMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("重要客户");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo("agent-001");
        assertThat(result.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("他人备注只有管理员可删 -> 普通坐席删他人备注抛异常")
    void deleteNote_otherOwner_nonAdmin_throws() {
        ConversationNoteEntity note = ConversationNoteEntity.builder()
                .id(1L).createdBy("agent-999").build();
        when(noteMapper.selectById(1L)).thenReturn(note);

        assertThatThrownBy(() -> service.deleteNote(1L, "agent-001", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
    }

    @Test
    @DisplayName("管理员可删任意备注")
    void deleteNote_admin_success() {
        ConversationNoteEntity note = ConversationNoteEntity.builder()
                .id(1L).createdBy("agent-999").build();
        when(noteMapper.selectById(1L)).thenReturn(note);

        service.deleteNote(1L, "admin-001", true);  // isAdmin=true

        verify(noteMapper).deleteById(1L);
    }

    @Test
    @DisplayName("修改他人备注 -> 抛异常")
    void updateNote_otherOwner_throws() {
        ConversationNoteEntity note = ConversationNoteEntity.builder()
                .id(1L).createdBy("agent-999").build();
        when(noteMapper.selectById(1L)).thenReturn(note);

        assertThatThrownBy(() -> service.updateNote(1L, "agent-001", "新内容"))
                .isInstanceOf(BusinessException.class);
    }
}
