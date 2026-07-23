package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.domain.model.BreachCandidate;
import com.aria.conversation.domain.model.SlaBreachActions;
import com.aria.conversation.domain.service.IBusinessHoursCalculator;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.domain.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlaBreachEvaluatorTest {

    @Mock IBusinessHoursCalculator businessHoursCalculator;

    SlaBreachEvaluator evaluator;

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 22, 10, 0, 0, 0, ZoneOffset.ofHours(8));

    @BeforeEach
    void setUp() {
        evaluator = new SlaBreachEvaluator(businessHoursCalculator);
    }

    private SlaPolicyEntity policy(int waitSec, int frtSec, int handleSec, int pct) {
        SlaPolicyEntity p = new SlaPolicyEntity();
        p.setId(1L);
        p.setWaitTimeTargetSec(waitSec);
        p.setFrtTargetSec(frtSec);
        p.setHandleTimeTargetSec(handleSec);
        p.setWarningThresholdPct(pct);
        p.setTimeMode("CALENDAR");
        p.setActions(new SlaBreachActions());
        return p;
    }

    @Test
    @DisplayName("WAITING 且等待时间超过阈值 -> WAIT BREACH")
    void evaluate_waitBreach() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s1");
        session.setStatus(SessionStatus.WAITING);
        session.setStartedAt(NOW.minusSeconds(130));  // 130s 前开始，阈值 120s

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(BreachType.WAIT);
        assertThat(result.get(0).stage()).isEqualTo(BreachStage.BREACH);
        assertThat(result.get(0).actualSec()).isEqualTo(130);
    }

    @Test
    @DisplayName("WAITING 且等待时间在预警区间 -> WAIT WARNING")
    void evaluate_waitWarning() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s2");
        session.setStatus(SessionStatus.WAITING);
        session.setStartedAt(NOW.minusSeconds(100));  // 100s, 阈值 120s, 80% = 96s

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).stage()).isEqualTo(BreachStage.WARNING);
    }

    @Test
    @DisplayName("WAITING 但时间未到预警线 -> 空列表")
    void evaluate_withinThreshold_empty() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s3");
        session.setStatus(SessionStatus.WAITING);
        session.setStartedAt(NOW.minusSeconds(30));  // 30s, 预警 96s

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE 且 firstReplyAt 为 null 且超过 FRT 阈值 -> FRT BREACH")
    void evaluate_frtBreach() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s4");
        session.setStatus(SessionStatus.ACTIVE);
        session.setAcceptedAt(NOW.minusSeconds(70));   // 70s 前接受，FRT 阈值 60s
        session.setFirstReplyAt(null);

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(BreachType.FRT);
        assertThat(result.get(0).stage()).isEqualTo(BreachStage.BREACH);
    }

    @Test
    @DisplayName("ACTIVE 且 acceptedAt 为 null -> FRT/HANDLE 均跳过，返回空列表（无异常）")
    void evaluate_acceptedAtNull_skipQuietly() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s5");
        session.setStatus(SessionStatus.ACTIVE);
        session.setAcceptedAt(null);  // 数据异常

        List<BreachCandidate> result = evaluator.evaluate(session, policy(120, 60, 1800, 80), NOW);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE 且 BUSINESS_HOURS 模式 -> 调用 businessHoursCalculator")
    void evaluate_businessHoursMode_callsCalculator() {
        ConversationEntity session = new ConversationEntity();
        session.setSessionId("s6");
        session.setStatus(SessionStatus.ACTIVE);
        session.setAcceptedAt(NOW.minusSeconds(200));
        session.setFirstReplyAt(null);

        SlaPolicyEntity p = policy(120, 60, 1800, 80);
        p.setTimeMode("BUSINESS_HOURS");

        when(businessHoursCalculator.calcBusinessSeconds(any(), any())).thenReturn(70L);

        List<BreachCandidate> result = evaluator.evaluate(session, p, NOW);

        // 70 > 60 (FRT), 70 > 48 (FRT warn), 70 < 1800 (HANDLE target), 70 > 1440 (HANDLE warn? no)
        assertThat(result).extracting(BreachCandidate::type).contains(BreachType.FRT);
    }
}
