package com.aria.conversation.application.query;

import com.aria.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话记录分页查询参数。
 *
 * <p>用于会话查询页面（/session/history），支持按时间范围、状态、客服、访客、标签等条件筛选。
 * page 为 0-based 页码索引。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationPageQuery extends PageQuery {

    /** 起始日期（yyyy-MM-dd），筛选 startedAt >= startDate */
    private String startDate;

    /** 结束日期（yyyy-MM-dd），筛选 startedAt <= endDate */
    private String endDate;

    /** 会话状态（逗号分隔多选）：AI_CHAT / WAITING / ACTIVE / CLOSED */
    private String status;

    /** 客服 ID 精确匹配，为空则不限 */
    private String agentId;

    /** 客服 ID 多选（逗号分隔），为空则不限；与 agentId 同时存在时二者取交集 */
    private String agentIds;

    /** 关键词（模糊匹配访客名称、会话ID） */
    private String keyword;

    /** 问题标签 */
    private String tag;

    /** 结束方：AGENT / VISITOR / SYSTEM */
    private String closedBy;
}
