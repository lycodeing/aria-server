package com.aria.conversation.infrastructure.scheduler;

import com.aria.conversation.domain.model.BreachCandidate;
import com.aria.conversation.domain.model.BreachStage;
import com.aria.conversation.domain.model.BreachType;
import com.aria.conversation.infrastructure.persistence.entity.SlaBreachEntity;
import com.aria.conversation.infrastructure.persistence.mapper.SlaBreachMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaBreachRecorderTest {

    @Mock SlaBreachMapper slaBreachMapper;

    SlaBreachRecorder recorder;

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 22, 10, 0, 0, 0, ZoneOffset.ofHours(8));

    @BeforeEach
    void setUp() {
        recorder = new SlaBreachRecorder(slaBreachMapper);
    }

    private BreachCandidate candidate(BreachType type, BreachStage stage) {
        return new BreachCandidate("sess-001", type, stage, 120, 96, 130, NOW);
    }

    @Test
    @DisplayName("新违规 -> 写入 DB 并返回实体")
    void record_newBreach_insertsAndReturns() {
        when(slaBreachMapper.existsBySessionTypeAndStage("sess-001", BreachType.WAIT, BreachStage.BREACH))
                .thenReturn(false);
        doAnswer(inv -> { ((SlaBreachEntity) inv.getArgument(0)).setId(1L); return 1; })
                .when(slaBreachMapper).insert(any(SlaBreachEntity.class));

        Optional<SlaBreachEntity> result =
                recorder.record(candidate(BreachType.WAIT, BreachStage.BREACH), 1L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
        verify(slaBreachMapper).insert(any(SlaBreachEntity.class));
    }

    @Test
    @DisplayName("WARNING 已存在 -> 不阻断 BREACH（三维幂等）")
    void record_warningExistsButBreachIsNew_inserts() {
        // WARNING 存在
        when(slaBreachMapper.existsBySessionTypeAndStage("sess-001", BreachType.WAIT, BreachStage.WARNING))
                .thenReturn(true);
        // BREACH 不存在
        when(slaBreachMapper.existsBySessionTypeAndStage("sess-001", BreachType.WAIT, BreachStage.BREACH))
                .thenReturn(false);

        doAnswer(inv -> { ((SlaBreachEntity) inv.getArgument(0)).setId(2L); return 1; })
                .when(slaBreachMapper).insert(any(SlaBreachEntity.class));

        // 记录 WARNING（幂等跳过）
        Optional<SlaBreachEntity> warningResult =
                recorder.record(candidate(BreachType.WAIT, BreachStage.WARNING), 1L);
        assertThat(warningResult).isEmpty();

        // 记录 BREACH（应写入）
        Optional<SlaBreachEntity> breachResult =
                recorder.record(candidate(BreachType.WAIT, BreachStage.BREACH), 1L);
        assertThat(breachResult).isPresent();
    }

    @Test
    @DisplayName("已存在相同 (sessionId, type, stage) -> 幂等跳过")
    void record_duplicate_returnsEmpty() {
        when(slaBreachMapper.existsBySessionTypeAndStage(any(), any(), any())).thenReturn(true);

        Optional<SlaBreachEntity> result =
                recorder.record(candidate(BreachType.FRT, BreachStage.BREACH), 1L);

        assertThat(result).isEmpty();
        verify(slaBreachMapper, never()).insert(any(SlaBreachEntity.class));
    }
}
