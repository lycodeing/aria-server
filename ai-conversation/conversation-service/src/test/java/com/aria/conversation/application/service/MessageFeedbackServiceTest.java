package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.feedback.MessageFeedbackDO;
import com.aria.conversation.infrastructure.feedback.MessageFeedbackMapper;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageFeedbackServiceTest {

    @Mock MessageFeedbackMapper feedbackMapper;
    @Mock ConversationMessageMapper messageMapper;
    @InjectMocks MessageFeedbackService service;

    // ------------------ seq 缺省场景 ------------------

    @Test
    void submit_seqOmitted_fallsBackToLastAssistantSeq() {
        when(messageMapper.selectLastAssistantSeq("sess_1")).thenReturn(Optional.of(42L));
        when(feedbackMapper.findBySessionAndSeq("sess_1", 42L)).thenReturn(Optional.empty());

        String result = service.submit("sess_1", null, "up", null);

        assertThat(result).isEqualTo("up");
        ArgumentCaptor<MessageFeedbackDO> cap = ArgumentCaptor.forClass(MessageFeedbackDO.class);
        verify(feedbackMapper).insert((MessageFeedbackDO) cap.capture());
        assertThat(cap.getValue().getSeq()).isEqualTo(42L);
        assertThat(cap.getValue().getFeedback()).isEqualTo("up");
    }

    @Test
    void submit_seqOmitted_noAssistantMessage_throws() {
        when(messageMapper.selectLastAssistantSeq("sess_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit("sess_1", null, "up", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂无可反馈的消息");
    }

    // ------------------ 显式 seq 场景 ------------------

    @Test
    void submit_explicitSeq_insertsRow() {
        when(feedbackMapper.findBySessionAndSeq("sess_1", 5L)).thenReturn(Optional.empty());

        service.submit("sess_1", 5L, "down", "v_123");

        ArgumentCaptor<MessageFeedbackDO> cap = ArgumentCaptor.forClass(MessageFeedbackDO.class);
        verify(feedbackMapper).insert((MessageFeedbackDO) cap.capture());
        assertThat(cap.getValue().getSessionId()).isEqualTo("sess_1");
        assertThat(cap.getValue().getSeq()).isEqualTo(5L);
        assertThat(cap.getValue().getFeedback()).isEqualTo("down");
        assertThat(cap.getValue().getVisitorId()).isEqualTo("v_123");
        verifyNoInteractions(messageMapper);
    }

    @Test
    void submit_existingRow_updatesInPlace() {
        MessageFeedbackDO existing = new MessageFeedbackDO();
        existing.setId(9L);
        existing.setSessionId("sess_1");
        existing.setSeq(5L);
        existing.setFeedback("up");
        when(feedbackMapper.findBySessionAndSeq("sess_1", 5L)).thenReturn(Optional.of(existing));

        String result = service.submit("sess_1", 5L, "down", null);

        assertThat(result).isEqualTo("down");
        ArgumentCaptor<MessageFeedbackDO> cap = ArgumentCaptor.forClass(MessageFeedbackDO.class);
        verify(feedbackMapper).updateById((MessageFeedbackDO) cap.capture());
        assertThat(cap.getValue().getFeedback()).isEqualTo("down");
        verify(feedbackMapper, never()).insert(any(MessageFeedbackDO.class));
    }

    // ------------------ 并发竞态：DuplicateKeyException 兜底 ------------------

    @Test
    void submit_concurrentInsert_recoversViaUpdate() {
        // 首次查询返回空 → 走 insert 分支；insert 因并发命中唯一索引抛 DuplicateKeyException
        // 兜底再查一次得到已存在的行 → 转成 update，最终不抛异常
        MessageFeedbackDO racedIn = new MessageFeedbackDO();
        racedIn.setId(99L);
        racedIn.setSessionId("sess_1");
        racedIn.setSeq(7L);
        racedIn.setFeedback("up");

        when(feedbackMapper.findBySessionAndSeq("sess_1", 7L))
                .thenReturn(Optional.empty())          // 第一次：未看到并发插入
                .thenReturn(Optional.of(racedIn));     // 第二次：并发方已入库
        doThrow(new org.springframework.dao.DuplicateKeyException("uq_msg_feedback"))
                .when(feedbackMapper).insert(any(MessageFeedbackDO.class));

        String result = service.submit("sess_1", 7L, "down", null);

        assertThat(result).isEqualTo("down");
        verify(feedbackMapper).insert(any(MessageFeedbackDO.class));
        verify(feedbackMapper).updateById(any(MessageFeedbackDO.class));
    }

    @Test
    void submit_duplicateKeyButRowGone_rethrows() {
        // 极端情况：insert 抛 DuplicateKey，但兜底查询又是空（DB 状态被外部清理）→ 直接抛原异常
        when(feedbackMapper.findBySessionAndSeq("sess_1", 8L)).thenReturn(Optional.empty());
        doThrow(new org.springframework.dao.DuplicateKeyException("uq_msg_feedback"))
                .when(feedbackMapper).insert(any(MessageFeedbackDO.class));

        assertThatThrownBy(() -> service.submit("sess_1", 8L, "up", null))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ------------------ 取消反馈 ------------------

    @Test
    void submit_feedbackNull_deletesRow() {
        when(feedbackMapper.deleteBySessionAndSeq("sess_1", 5L)).thenReturn(1);

        String result = service.submit("sess_1", 5L, null, null);

        assertThat(result).isNull();
        verify(feedbackMapper).deleteBySessionAndSeq("sess_1", 5L);
        verify(feedbackMapper, never()).insert(any(MessageFeedbackDO.class));
        verify(feedbackMapper, never()).updateById(any(MessageFeedbackDO.class));
    }

    // ------------------ 参数校验 ------------------

    @Test
    void submit_invalidFeedback_throws() {
        assertThatThrownBy(() -> service.submit("sess_1", 5L, "excellent", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("feedback");
    }

    @Test
    void submit_seqZero_throws() {
        assertThatThrownBy(() -> service.submit("sess_1", 0L, "up", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("seq");
    }

    @Test
    void submit_seqNegative_throws() {
        assertThatThrownBy(() -> service.submit("sess_1", -1L, "up", null))
                .isInstanceOf(BusinessException.class);
    }
}
