package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.persistence.entity.ConversationNoteEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationNoteMapper;
import com.aria.conversation.interfaces.rest.vo.NoteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NoteAppService {

    private static final int NOT_FOUND = 40400;
    private static final int FORBIDDEN = 40300;

    private final ConversationNoteMapper noteMapper;

    public List<NoteVO> listNotes(String sessionId) {
        return noteMapper.selectBySessionId(sessionId).stream()
                .map(this::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public NoteVO addNote(String sessionId, String operatorId, String content) {
        ConversationNoteEntity entity = ConversationNoteEntity.builder()
                .sessionId(sessionId).content(content).createdBy(operatorId).build();
        noteMapper.insert(entity);
        return toVO(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public NoteVO updateNote(Long noteId, String operatorId, String content) {
        ConversationNoteEntity note = requireNote(noteId);
        if (!note.getCreatedBy().equals(operatorId)) {
            throw new BusinessException(FORBIDDEN, "无权修改他人备注");
        }
        note.setContent(content);
        noteMapper.updateById(note);
        return toVO(noteMapper.selectById(noteId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteNote(Long noteId, String operatorId, boolean isAdmin) {
        ConversationNoteEntity note = requireNote(noteId);
        if (!isAdmin && !note.getCreatedBy().equals(operatorId)) {
            throw new BusinessException(FORBIDDEN, "无权删除他人备注");
        }
        noteMapper.deleteById(noteId);
    }

    private ConversationNoteEntity requireNote(Long noteId) {
        ConversationNoteEntity note = noteMapper.selectById(noteId);
        if (note == null) throw new BusinessException(NOT_FOUND, "备注不存在: " + noteId);
        return note;
    }

    private NoteVO toVO(ConversationNoteEntity e) {
        return new NoteVO(
                e.getId(),
                e.getContent(),
                e.getCreatedBy(),
                e.getCreateTime() != null ? e.getCreateTime().atOffset(ZoneOffset.ofHours(8)) : null,
                e.getUpdateTime() != null ? e.getUpdateTime().atOffset(ZoneOffset.ofHours(8)) : null
        );
    }
}
