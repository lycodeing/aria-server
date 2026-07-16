package com.aria.conversation.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SessionStatusTransitionTest {

    @Test
    void aiChat_canTransitionTo_waiting() {
        assertThat(SessionStatus.AI_CHAT.transitionTo(SessionStatus.WAITING))
                .isEqualTo(SessionStatus.WAITING);
    }

    @Test
    void aiChat_canTransitionTo_closed() {
        // CLOSED 仍合法（AI 对话直接结束，原有路径保留）
        assertThat(SessionStatus.AI_CHAT.transitionTo(SessionStatus.CLOSED))
                .isEqualTo(SessionStatus.CLOSED);
    }
}
