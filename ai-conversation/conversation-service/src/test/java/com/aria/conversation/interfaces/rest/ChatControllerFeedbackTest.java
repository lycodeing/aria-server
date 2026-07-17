package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.ChatAppService;
import com.aria.conversation.application.service.MessageFeedbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatControllerFeedbackTest {

    @Mock ChatAppService chatService;
    @Mock MessageFeedbackService feedbackService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks ChatController controller;

    @Test
    void submitFeedback_upVote_delegatesToService() {
        ChatController.FeedbackRequest req = new ChatController.FeedbackRequest();
        req.setSessionId("sess_a");
        req.setSeq(10L);
        req.setFeedback("up");
        when(feedbackService.submit("sess_a", 10L, "up", null)).thenReturn("up");

        R<Map<String, Object>> r = controller.submitFeedback(req);

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data()).containsEntry("feedback", "up");
        verify(feedbackService).submit("sess_a", 10L, "up", null);
    }

    @Test
    void submitFeedback_cancel_returnsNullFeedback() {
        ChatController.FeedbackRequest req = new ChatController.FeedbackRequest();
        req.setSessionId("sess_a");
        req.setSeq(10L);
        req.setFeedback(null);
        when(feedbackService.submit("sess_a", 10L, null, null)).thenReturn(null);

        R<Map<String, Object>> r = controller.submitFeedback(req);

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data()).containsEntry("feedback", null);
    }

    @Test
    void submitFeedback_seqOmitted_delegatesWithNullSeq() {
        ChatController.FeedbackRequest req = new ChatController.FeedbackRequest();
        req.setSessionId("sess_a");
        req.setFeedback("down");
        when(feedbackService.submit("sess_a", null, "down", null)).thenReturn("down");

        controller.submitFeedback(req);

        verify(feedbackService).submit("sess_a", null, "down", null);
    }
}
