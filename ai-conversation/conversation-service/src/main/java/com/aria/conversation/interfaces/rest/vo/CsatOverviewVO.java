package com.aria.conversation.interfaces.rest.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CSAT 概览指标 VO。
 *
 * <p>用于 {@code GET /api/v1/dashboard/csat-overview} 接口，
 * 汇总指定时间范围内的 CSAT 核心统计数据。
 *
 * @author aria
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsatOverviewVO {

    /** 平均 CSAT 分（1.0–5.0，无数据时为 0.0） */
    private double avgScore;

    /** 评价响应率（已评价 / 全部已完结邀请，0.0–1.0） */
    private double responseRate;

    /**
     * 满意率（score ≥ 4 的评价 / 全部已评价，0.0–1.0）。
     * 通常用于衡量"好评率"。
     */
    private double satisfactionRate;

    /** 已评价数（status = RATED） */
    private long ratedCount;

    /**
     * 总邀请完结数（status IN ('RATED','EXPIRED','SKIPPED')）。
     * 不含仍在等待中的 PENDING 记录，用于计算响应率分母。
     */
    private long totalInvitations;

    /** 当前待评价数（status = PENDING，含未过期记录） */
    private long pendingCount;

    /** 已过期数（status = EXPIRED） */
    private long expiredCount;

    /** 已跳过数（status = SKIPPED） */
    private long skippedCount;
}
