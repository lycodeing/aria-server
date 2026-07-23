package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.model.BreachCandidate;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * SLA 违规持久化组件。
 *
 * <p>幂等写入：按 (session_id, breach_type, stage) 三维检查，
 * WARNING 入库不阻断后续 BREACH 写入（两者 stage 不同）。
 */
@Component
@RequiredArgsConstructor
public class SlaBreachRecorder {

    private final SlaBreachMapper slaBreachMapper;

    /**
     * 幂等写入一条违规记录。
     *
     * @param candidate 违规候选值对象
     * @param policyId  关联的 SLA 策略主键
     * @return 写入成功返回实体（含自增 id），已存在则返回 empty
     */
    public Optional<SlaBreachEntity> record(BreachCandidate candidate, Long policyId) {
        if (slaBreachMapper.existsBySessionTypeAndStage(
                candidate.sessionId(), candidate.type(), candidate.stage())) {
            return Optional.empty();
        }
        SlaBreachEntity entity = SlaBreachEntity.builder()
                .sessionId(candidate.sessionId())
                .policyId(policyId)
                .breachType(candidate.type().name())
                .stage(candidate.stage().name())
                .targetSec(candidate.targetSec())
                .warnAtSec(candidate.warnAtSec())
                .actualSec(candidate.actualSec())
                .breachAt(candidate.detectedAt())
                .build();
        slaBreachMapper.insert(entity);
        return Optional.of(entity);
    }

    /**
     * 标记 SSE 告警已发送时间。
     *
     * @param breachId cs_sla_breach 主键
     * @param at       告警发送时间
     */
    public void markAlerted(Long breachId, OffsetDateTime at) {
        slaBreachMapper.updateAlertedAt(breachId, at);
    }

    /**
     * 标记升级执行时间。
     *
     * @param breachId cs_sla_breach 主键
     * @param at       升级执行时间
     */
    public void markEscalated(Long breachId, OffsetDateTime at) {
        slaBreachMapper.updateEscalatedAt(breachId, at);
    }
}
