package com.aria.conversation.interfaces.rest.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话状态分布数据项 VO。
 *
 * <p>用于前端饼图展示会话状态（WAITING / ACTIVE / CLOSED）的分布占比。
 *
 * @author aria
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusDistributionItemVO {

    /** 状态名称（WAITING / ACTIVE / CLOSED） */
    private String status;

    /** 该状态的会话数量 */
    private long count;
}
