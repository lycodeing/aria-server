package com.aria.conversation.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.conversation.infrastructure.feedback.MessageFeedbackDO;
import com.aria.conversation.infrastructure.feedback.MessageFeedbackMapper;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

/**
 * 消息反馈应用服务（访客对 AI/座席消息的 up/down）。
 *
 * <p>业务规则：
 * <ul>
 *   <li>seq 缺省时回落到当前 session 最近一条回复（assistant 或 agent）的 seq；仍缺失 → 400</li>
 *   <li>feedback 为 null 表示取消反馈：删除已有行（幂等，不存在也返回成功）</li>
 *   <li>feedback 为 up/down：{@code (sessionId, seq)} 已存在则 update，否则 insert</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageFeedbackService {

    private static final int INVALID_PARAM = 40000;
    private static final int NOT_FOUND     = 40400;

    private static final Set<String> ALLOWED_FEEDBACK = Set.of("up", "down");

    private final MessageFeedbackMapper feedbackMapper;
    private final ConversationMessageMapper messageMapper;

    /**
     * 提交或取消消息反馈。
     *
     * @param sessionId 会话 ID（调用方保证格式已校验）
     * @param seq       目标消息的 seq；null 时后端回落到最近一条回复（assistant/agent）
     * @param feedback  {@code "up"} / {@code "down"} / null（取消）
     * @param visitorId 访客标识（可 null）
     * @return 反馈落库后的最终值（up/down/null 三态），供前端确认服务端状态
     */
    @Transactional(rollbackFor = Exception.class)
    public String submit(String sessionId, Long seq, String feedback, String visitorId) {
        if (feedback != null && !ALLOWED_FEEDBACK.contains(feedback)) {
            throw new BusinessException(INVALID_PARAM, "feedback 必须为 up / down 或 null");
        }

        long resolvedSeq = resolveSeq(sessionId, seq);

        if (feedback == null) {
            feedbackMapper.deleteBySessionAndSeq(sessionId, resolvedSeq);
            log.info("[Feedback] 取消 sessionId={} seq={}", sessionId, resolvedSeq);
            return null;
        }

        Optional<MessageFeedbackDO> existing = feedbackMapper.findBySessionAndSeq(sessionId, resolvedSeq);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (existing.isPresent()) {
            updateExisting(existing.get(), feedback, now);
        } else {
            try {
                insertNew(sessionId, resolvedSeq, feedback, visitorId, now);
            } catch (DuplicateKeyException race) {
                // 并发场景：另一线程刚插入相同 (sessionId, seq)，DB uq_msg_feedback 兜底。
                // 重新查询后走 update 路径，保证 last-write-wins 而非抛 500。
                MessageFeedbackDO row = feedbackMapper.findBySessionAndSeq(sessionId, resolvedSeq)
                        .orElseThrow(() -> race);  // 仍不存在只能是唯一约束外的失败，向上抛
                updateExisting(row, feedback, now);
                log.debug("[Feedback] 并发冲突已通过 update 兜底 sessionId={} seq={}", sessionId, resolvedSeq);
            }
        }
        log.info("[Feedback] 提交 sessionId={} seq={} feedback={}", sessionId, resolvedSeq, feedback);
        return feedback;
    }

    private void updateExisting(MessageFeedbackDO row, String feedback, OffsetDateTime now) {
        row.setFeedback(feedback);
        row.setUpdatedAt(now);
        feedbackMapper.updateById(row);
    }

    private void insertNew(String sessionId, long seq, String feedback,
                           String visitorId, OffsetDateTime now) {
        MessageFeedbackDO row = new MessageFeedbackDO();
        row.setSessionId(sessionId);
        row.setSeq(seq);
        row.setFeedback(feedback);
        row.setVisitorId(visitorId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        feedbackMapper.insert(row);
    }

    private long resolveSeq(String sessionId, Long seq) {
        if (seq != null) {
            if (seq <= 0) {
                throw new BusinessException(INVALID_PARAM, "seq 必须为正整数");
            }
            return seq;
        }
        return messageMapper.selectLastReplySeq(sessionId)
                .orElseThrow(() -> new BusinessException(NOT_FOUND, "会话中暂无可反馈的消息"));
    }
}
