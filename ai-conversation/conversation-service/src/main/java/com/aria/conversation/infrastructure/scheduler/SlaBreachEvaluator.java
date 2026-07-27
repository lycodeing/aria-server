package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.SessionStatus;
import com.aria.conversation.domain.model.BreachCandidate;
import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.domain.service.IBusinessHoursCalculator;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SLA 违规评估器。无状态计算组件，不包含任何 I/O。
 *
 * <p>通过 {@link IBusinessHoursCalculator} 接口获取业务时间计算能力（依赖倒置）。
 * 支持两阶段检测：
 * <ul>
 *   <li>{@link BreachStage#WARNING} — 达到目标时间的 warningThresholdPct% 时触发预警</li>
 *   <li>{@link BreachStage#BREACH}  — 超过目标时间时触发正式违规</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaBreachEvaluator {

    private static final int PCT_DIVISOR = 100;

    private final IBusinessHoursCalculator businessHoursCalculator;

    /**
     * 对单个会话执行全量 SLA 违规评估。
     *
     * @param session 待检测会话
     * @param policy  命中的 SLA 策略（调用方保证非 null）
     * @param now     检测基准时间（由调度器统一捕获，避免各指标时间漂移）
     * @return 本次产生的违规候选列表，无违规返回空列表（不返回 null）
     */
    public List<BreachCandidate> evaluate(ConversationEntity session,
                                          SlaPolicyEntity policy,
                                          OffsetDateTime now) {
        // null-safe：DB 列有 NOT NULL DEFAULT 80，但防御遗留脏数据或手动修改导致的 null
        int warningPct = resolveWarningPct(policy);
        List<BreachCandidate> results = new ArrayList<>();
        evaluateWait(session, policy, warningPct, now).ifPresent(results::add);
        evaluateFrt(session, policy, warningPct, now).ifPresent(results::add);
        evaluateHandle(session, policy, warningPct, now).ifPresent(results::add);
        return results;
    }

    /**
     * 安全读取 warningThresholdPct。
     * 数据库列为 NOT NULL DEFAULT 80，正常情况不会为 null；
     * 若遇到遗留脏数据，退回 100（warnAtSec == targetSec，即 WARNING 与 BREACH 同时触发，等效禁用预警），
     * 并输出 WARN 日志方便排查。
     */
    private int resolveWarningPct(SlaPolicyEntity policy) {
        Integer pct = policy.getWarningThresholdPct();
        if (pct == null) {
            log.warn("[SLA] policy={} warningThresholdPct is null, WARNING stage disabled (defaulting to 100)",
                    policy.getId());
            return 100;
        }
        return pct;
    }

    // ── 三个指标的独立检测方法 ─────────────────────────────────────────────────

    private Optional<BreachCandidate> evaluateWait(ConversationEntity session,
                                                    SlaPolicyEntity policy,
                                                    int warningPct,
                                                    OffsetDateTime now) {
        if (session.getStatus() != SessionStatus.WAITING) {
            return Optional.empty();
        }
        long elapsed = calcElapsed(session.getStartedAt(), now, policy.getTimeMode());
        return resolveStage(elapsed, policy.getWaitTimeTargetSec(),
                warningPct, BreachType.WAIT, session, now);
    }

    private Optional<BreachCandidate> evaluateFrt(ConversationEntity session,
                                                   SlaPolicyEntity policy,
                                                   int warningPct,
                                                   OffsetDateTime now) {
        if (session.getStatus() != SessionStatus.ACTIVE) {
            return Optional.empty();
        }
        if (session.getAcceptedAt() == null) {
            log.warn("[SLA] ACTIVE session missing acceptedAt, skipping FRT. session={}",
                    session.getSessionId());
            return Optional.empty();
        }
        // FRT 只在座席尚未首次回复时计算
        if (session.getFirstReplyAt() != null) {
            return Optional.empty();
        }
        long elapsed = calcElapsed(session.getAcceptedAt(), now, policy.getTimeMode());
        return resolveStage(elapsed, policy.getFrtTargetSec(),
                warningPct, BreachType.FRT, session, now);
    }

    private Optional<BreachCandidate> evaluateHandle(ConversationEntity session,
                                                      SlaPolicyEntity policy,
                                                      int warningPct,
                                                      OffsetDateTime now) {
        if (session.getStatus() != SessionStatus.ACTIVE) {
            return Optional.empty();
        }
        if (session.getAcceptedAt() == null) {
            log.warn("[SLA] ACTIVE session missing acceptedAt, skipping HANDLE. session={}",
                    session.getSessionId());
            return Optional.empty();
        }
        long elapsed = calcElapsed(session.getAcceptedAt(), now, policy.getTimeMode());
        return resolveStage(elapsed, policy.getHandleTimeTargetSec(),
                warningPct, BreachType.HANDLE, session, now);
    }

    // ── 通用辅助方法 ──────────────────────────────────────────────────────────

    /**
     * 判断违规阶段并构建候选对象。
     *
     * <p>使用 {@code (long) targetSec * warningPct} 防止 int 乘法溢出（M-R3-2）。
     */
    private Optional<BreachCandidate> resolveStage(long elapsed, int targetSec,
                                                    int warningPct, BreachType type,
                                                    ConversationEntity session,
                                                    OffsetDateTime now) {
        int warnAtSec = (int) ((long) targetSec * warningPct / PCT_DIVISOR);
        if (elapsed >= targetSec) {
            return Optional.of(buildCandidate(session, type, BreachStage.BREACH,
                    targetSec, warnAtSec, elapsed, now));
        }
        if (elapsed >= warnAtSec) {
            return Optional.of(buildCandidate(session, type, BreachStage.WARNING,
                    targetSec, warnAtSec, elapsed, now));
        }
        return Optional.empty();
    }

    /**
     * 计算已用时间（秒）。
     * CALENDAR 模式直接计算自然秒数；BUSINESS_HOURS 模式委托给 {@link IBusinessHoursCalculator}。
     */
    private long calcElapsed(OffsetDateTime start, OffsetDateTime now, String timeMode) {
        return "BUSINESS_HOURS".equals(timeMode)
                ? businessHoursCalculator.calcBusinessSeconds(start, now)
                : ChronoUnit.SECONDS.between(start, now);
    }

    private BreachCandidate buildCandidate(ConversationEntity session, BreachType type,
                                           BreachStage stage, int targetSec, int warnAtSec,
                                           long actualSec, OffsetDateTime detectedAt) {
        return new BreachCandidate(
                session.getSessionId(),
                type,
                stage,
                targetSec,
                warnAtSec,
                (int) actualSec,
                detectedAt
        );
    }
}
