package com.aria.conversation.infrastructure.feedback;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 坐席反馈记录（对应 cs_conversation.cs_session_feedback 表）。
 *
 * <p>坐席对 AI 回答的纠错/点赞反馈。WRONG_INTENT 触发意图样本回写，
 * WRONG_ANSWER 目前仅落库（KB 审核队列 P1 迭代），GOOD 仅计数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(schema = "cs_conversation", value = "cs_session_feedback")
public class SessionFeedbackEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    /** 被标记的 AI 消息 ID，可为 null */
    private String messageId;

    /** WRONG_INTENT | WRONG_ANSWER | GOOD */
    private String feedbackType;

    /** 用户原始消息 */
    private String originalQuery;

    /** 坐席填写的正确意图 code（WRONG_INTENT 时必填） */
    private String correctIntent;

    /** 坐席填写的正确回答（WRONG_ANSWER 时必填） */
    private String correctAnswer;

    /** 操作坐席 ID（Sa-Token 登录态） */
    private Long agentId;

    /** 是否已写入 intent_example_vectors */
    private Boolean accumulated;

    /** 是否已推入 KB 审核队列（当前恒为 false，KB 审核队列 P1 迭代补全后启用） */
    private Boolean kbQueued;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
