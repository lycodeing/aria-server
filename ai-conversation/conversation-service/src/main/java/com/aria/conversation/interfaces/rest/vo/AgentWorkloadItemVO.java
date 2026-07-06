package com.aria.conversation.interfaces.rest.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 座席工作量统计项 VO。
 *
 * <p>用于前端展示各座席的会话接待量，按总会话数降序排列。
 *
 * @author aria
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentWorkloadItemVO {

    /** 座席 ID */
    private String agentId;

    /** 总接待会话数 */
    private long totalSessions;

    /** 当前 ACTIVE 会话数 */
    private long activeSessions;
}
