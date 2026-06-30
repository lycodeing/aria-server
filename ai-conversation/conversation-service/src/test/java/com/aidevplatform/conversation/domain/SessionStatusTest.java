package com.aidevplatform.conversation.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SessionStatus} 状态机转换单元测试。
 *
 * <p>覆盖：合法转换路径、全部非法转换路径、self-transition。
 */
@DisplayName("SessionStatus 状态机")
class SessionStatusTest {

    // ---- 合法转换 ----

    @Test
    @DisplayName("WAITING → ACTIVE 合法")
    void waitingToActive_ok() {
        SessionStatus result = SessionStatus.WAITING.transitionTo(SessionStatus.ACTIVE);
        assertEquals(SessionStatus.ACTIVE, result);
    }

    @Test
    @DisplayName("ACTIVE → CLOSED 合法")
    void activeToClose_ok() {
        SessionStatus result = SessionStatus.ACTIVE.transitionTo(SessionStatus.CLOSED);
        assertEquals(SessionStatus.CLOSED, result);
    }

    // ---- 非法转换 ----

    @Test
    @DisplayName("WAITING → CLOSED 合法（未接入直接取消）")
    void waitingToClosed_ok() {
        SessionStatus result = SessionStatus.WAITING.transitionTo(SessionStatus.CLOSED);
        assertEquals(SessionStatus.CLOSED, result);
    }

    @Test
    @DisplayName("ACTIVE → WAITING 非法")
    void activeToWaiting_throws() {
        assertThrows(IllegalStateException.class,
                () -> SessionStatus.ACTIVE.transitionTo(SessionStatus.WAITING));
    }

    @Test
    @DisplayName("CLOSED → ACTIVE 非法")
    void closedToActive_throws() {
        assertThrows(IllegalStateException.class,
                () -> SessionStatus.CLOSED.transitionTo(SessionStatus.ACTIVE));
    }

    @Test
    @DisplayName("CLOSED → WAITING 非法")
    void closedToWaiting_throws() {
        assertThrows(IllegalStateException.class,
                () -> SessionStatus.CLOSED.transitionTo(SessionStatus.WAITING));
    }

    // ---- self-transition ----

    @Test
    @DisplayName("WAITING → WAITING self-transition 非法")
    void waitingToWaiting_throws() {
        assertThrows(IllegalStateException.class,
                () -> SessionStatus.WAITING.transitionTo(SessionStatus.WAITING));
    }

    @Test
    @DisplayName("ACTIVE → ACTIVE self-transition 非法")
    void activeToActive_throws() {
        assertThrows(IllegalStateException.class,
                () -> SessionStatus.ACTIVE.transitionTo(SessionStatus.ACTIVE));
    }
}
