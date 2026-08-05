package com.aria.conversation.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 坐席反馈提交请求。
 *
 * <p>坐席对 AI 回答标记纠错或点赞：
 * <ul>
 *   <li>{@code WRONG_INTENT}：意图识别错，{@code correctIntent} 必填，触发样本回写</li>
 *   <li>{@code WRONG_ANSWER}：回答内容错，{@code correctAnswer} 必填，落库待 KB 审核</li>
 *   <li>{@code GOOD}：正向反馈，仅计数</li>
 * </ul>
 */
@Data
public class SessionFeedbackRequest {

    @NotBlank
    private String sessionId;

    /** 被标记的 AI 消息 ID，可为 null */
    private String messageId;

    /** 反馈类型 */
    @NotNull
    private FeedbackType feedbackType;

    /** 用户原始消息，前端上下文已有 */
    @NotBlank
    private String originalQuery;

    /** feedbackType = WRONG_INTENT 时必填 */
    private String correctIntent;

    /** feedbackType = WRONG_ANSWER 时必填 */
    private String correctAnswer;

    public enum FeedbackType {
        WRONG_INTENT, WRONG_ANSWER, GOOD
    }
}
