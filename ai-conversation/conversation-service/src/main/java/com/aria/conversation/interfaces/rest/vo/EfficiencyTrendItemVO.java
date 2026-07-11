package com.aria.conversation.interfaces.rest.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 效率趋势数据项 VO（按天聚合）。
 * 用于前端效率趋势折线图，展示每天的三项响应时效均值。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EfficiencyTrendItemVO {

    /** 日期标签，格式 YYYY-MM-DD */
    private String date;

    /** 平均等待时长（秒），accepted_at - started_at */
    private long avgWaitSeconds;

    /** 平均处理时长（秒），ended_at - accepted_at */
    private long avgHandleSeconds;

    /** 平均首次回复时长（秒），first_reply_at - accepted_at */
    private long avgFirstReplySeconds;
}
