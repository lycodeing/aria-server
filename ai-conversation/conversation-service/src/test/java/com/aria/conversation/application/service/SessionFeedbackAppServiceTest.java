package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.ai.IntentAccumulationService;
import com.aria.conversation.infrastructure.feedback.SessionFeedbackEntity;
import com.aria.conversation.infrastructure.feedback.SessionFeedbackRepository;
import com.aria.conversation.infrastructure.observability.IntentMetricsRecorder;
import com.aria.conversation.interfaces.dto.SessionFeedbackRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionFeedbackAppService 坐席反馈分流")
class SessionFeedbackAppServiceTest {

    @Mock private SessionFeedbackRepository feedbackRepository;
    @Mock private IntentAccumulationService accumulationService;
    @Mock private IntentMetricsRecorder metricsRecorder;

    private SessionFeedbackAppService service;

    @BeforeEach
    void setUp() {
        service = new SessionFeedbackAppService(feedbackRepository, accumulationService, metricsRecorder);
    }

    private SessionFeedbackRequest req(SessionFeedbackRequest.FeedbackType type,
                                       String correctIntent) {
        SessionFeedbackRequest r = new SessionFeedbackRequest();
        r.setSessionId("sid-1");
        r.setOriginalQuery("帮我查一下快递");
        r.setFeedbackType(type);
        r.setCorrectIntent(correctIntent);
        return r;
    }

    @Test
    @DisplayName("WRONG_INTENT 且 correctIntent 有值：落库 + 计数 + 触发人工积累")
    void wrongIntent_shouldAccumulate() {
        service.submitFeedback(req(SessionFeedbackRequest.FeedbackType.WRONG_INTENT, "logistics_query"), 100L);

        verify(feedbackRepository).save(any(SessionFeedbackEntity.class));
        verify(metricsRecorder).recordFeedback("wrong_intent");
        verify(accumulationService).manualAccumulate(eq("logistics_query"), eq("帮我查一下快递"), any());
    }

    @Test
    @DisplayName("WRONG_INTENT 但 correctIntent 为空：不触发积累")
    void wrongIntent_missingCorrectIntent_shouldSkipAccumulate() {
        service.submitFeedback(req(SessionFeedbackRequest.FeedbackType.WRONG_INTENT, "  "), 100L);

        verify(feedbackRepository).save(any(SessionFeedbackEntity.class));
        verify(accumulationService, never()).manualAccumulate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("GOOD：仅落库 + 计数，无积累")
    void good_shouldOnlyPersistAndCount() {
        service.submitFeedback(req(SessionFeedbackRequest.FeedbackType.GOOD, null), 100L);

        verify(feedbackRepository).save(any(SessionFeedbackEntity.class));
        verify(metricsRecorder).recordFeedback("good");
        verify(accumulationService, never()).manualAccumulate(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("WRONG_ANSWER：落库 + 计数，当前不入队不积累")
    void wrongAnswer_shouldOnlyPersistAndCount() {
        SessionFeedbackRequest r = req(SessionFeedbackRequest.FeedbackType.WRONG_ANSWER, null);
        r.setCorrectAnswer("正确答案是……");
        service.submitFeedback(r, 100L);

        verify(feedbackRepository).save(any(SessionFeedbackEntity.class));
        verify(metricsRecorder).recordFeedback("wrong_answer");
        verify(accumulationService, never()).manualAccumulate(anyString(), anyString(), any());
    }
}
