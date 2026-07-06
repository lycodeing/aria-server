package com.aria.conversation.interfaces.rest.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话趋势数据项 VO（按月聚合）。
 *
 * <p>每条记录代表一个月的会话统计，用于前端折线图展示。
 * 区分人工会话和 AI 会话两条数据线。
 *
 * @author aria
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationTrendItemVO {

    /** 月份标签，如 "2026-07" */
    private String month;

    /** 人工会话数（有 agent_id 的会话） */
    private long humanCount;

    /** AI 会话数（无 agent_id 的会话，纯 AI 对话） */
    private long aiCount;
}
