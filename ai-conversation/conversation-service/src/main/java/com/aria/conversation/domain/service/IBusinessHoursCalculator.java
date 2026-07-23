package com.aria.conversation.domain.service;

import java.time.OffsetDateTime;

/**
 * 业务时间计算接口（domain 层）。
 * 由 infrastructure 层的 BusinessHoursService 实现，通过依赖倒置让
 * SlaBreachEvaluator（domain service）不直接依赖 infrastructure。
 */
public interface IBusinessHoursCalculator {
    /**
     * 计算 [start, end] 区间内的业务时间秒数，跳过非服务时段。
     *
     * @param start 计时起点
     * @param end   计时终点（通常为当前时间）
     * @return 业务时间内的秒数
     */
    long calcBusinessSeconds(OffsetDateTime start, OffsetDateTime end);
}
