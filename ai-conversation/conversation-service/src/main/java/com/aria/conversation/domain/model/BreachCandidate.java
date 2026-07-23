package com.aria.conversation.domain.model;

import java.time.OffsetDateTime;

/**
 * SLA 违规候选值对象（domain 层）。
 *
 * <p>由 SlaBreachEvaluator 计算产生，通过 SlaBreachRecorder 持久化，
 * 不包含任何 I/O 操作。
 *
 * @param sessionId   会话唯一标识
 * @param type        违规类型（WAIT / FRT / HANDLE）
 * @param stage       违规阶段（WARNING / BREACH）
 * @param targetSec   目标时间（秒）
 * @param warnAtSec   预警时间（秒），= targetSec × warningThresholdPct / 100
 * @param actualSec   实际已用时间（秒）
 * @param detectedAt  检测到违规的时间点
 */
public record BreachCandidate(
        String sessionId,
        BreachType type,
        BreachStage stage,
        int targetSec,
        int warnAtSec,
        int actualSec,
        OffsetDateTime detectedAt
) {}
