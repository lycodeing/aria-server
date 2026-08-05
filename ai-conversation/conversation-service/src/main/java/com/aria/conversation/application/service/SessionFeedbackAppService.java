package com.aria.conversation.application.service;

import com.aria.conversation.infrastructure.ai.IntentAccumulationService;
import com.aria.conversation.infrastructure.feedback.SessionFeedbackEntity;
import com.aria.conversation.infrastructure.feedback.SessionFeedbackRepository;
import com.aria.conversation.infrastructure.observability.IntentMetricsRecorder;
import com.aria.conversation.interfaces.dto.SessionFeedbackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 坐席反馈应用服务。
 *
 * <p>接收坐席对 AI 回答的纠错/点赞反馈，按类型分流：
 * <ul>
 *   <li>{@code WRONG_INTENT}：意图识别错，将正确意图 + 原始 query 通过
 *       {@link IntentAccumulationService#manualAccumulate} 异步写入样本库（autoConfirmed=false，
 *       人工确认，强化 Tier2 原型）；</li>
 *   <li>{@code WRONG_ANSWER}：回答内容错，当前仅落库，KB 审核队列 P1 迭代补全；</li>
 *   <li>{@code GOOD}：正向反馈，仅计数。</li>
 * </ul>
 *
 * <p>反馈计数指标统一由 {@link IntentMetricsRecorder#recordFeedback} 记录，
 * 与 P0-A 保持一致，业务类不直接操作 MeterRegistry。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionFeedbackAppService {

    private final SessionFeedbackRepository feedbackRepository;
    private final IntentAccumulationService accumulationService;
    private final IntentMetricsRecorder metricsRecorder;

    /**
     * 提交坐席反馈。
     *
     * @param req     反馈请求
     * @param agentId 当前登录坐席 ID（由 Controller 从 Sa-Token 取得）
     */
    public void submitFeedback(SessionFeedbackRequest req, Long agentId) {
        SessionFeedbackEntity entity = SessionFeedbackEntity.builder()
                .sessionId(req.getSessionId())
                .messageId(req.getMessageId())
                .feedbackType(req.getFeedbackType().name())
                .originalQuery(req.getOriginalQuery())
                .correctIntent(req.getCorrectIntent())
                .correctAnswer(req.getCorrectAnswer())
                .agentId(agentId)
                .accumulated(false)
                .kbQueued(false)
                .build();
        feedbackRepository.save(entity);

        metricsRecorder.recordFeedback(req.getFeedbackType().name().toLowerCase());

        switch (req.getFeedbackType()) {
            case WRONG_INTENT -> handleWrongIntent(entity, req);
            case WRONG_ANSWER -> handleWrongAnswer(req);
            case GOOD -> { /* 仅计数，无后续写入 */ }
        }
    }

    /**
     * 意图纠错：正确意图 + 原始 query 异步写入样本库，成功后回标 accumulated。
     */
    private void handleWrongIntent(SessionFeedbackEntity entity, SessionFeedbackRequest req) {
        if (!StringUtils.hasText(req.getCorrectIntent())) {
            log.warn("[Feedback] WRONG_INTENT 但 correctIntent 为空，跳过积累 session={}", req.getSessionId());
            return;
        }
        accumulationService.manualAccumulate(
                req.getCorrectIntent(),
                req.getOriginalQuery(),
                () -> feedbackRepository.markAccumulated(entity.getId()));
    }

    /**
     * 回答纠错：占位实现，当前仅记录日志，kb_queued 维持 false。
     * KB 审核队列接口就绪后（P1）补全入队逻辑。
     */
    private void handleWrongAnswer(SessionFeedbackRequest req) {
        log.info("[Feedback] WRONG_ANSWER 待入 KB 审核队列（P1）session={} answerLen={}",
                req.getSessionId(),
                req.getCorrectAnswer() == null ? 0 : req.getCorrectAnswer().length());
    }
}
