package com.aria.conversation.domain.model;

/**
 * SLA 违规阶段。
 *
 * <ul>
 *   <li>{@link #WARNING} — 预警阶段（达到 warningThresholdPct% 时触发）</li>
 *   <li>{@link #BREACH}  — 正式违规（超过目标时间）</li>
 * </ul>
 */
public enum BreachStage {
    WARNING,
    BREACH
}
