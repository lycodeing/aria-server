package com.aria.conversation.domain;

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

    // ---- AI_CHAT 合法转换 ----

    @Test
    @DisplayName("AI_CHAT → WAITING 合法（用户转人工）")
    void aiChatToWaiting_ok() {
        SessionStatus result = SessionStatus.AI_CHAT.transitionTo(SessionStatus.WAITING);
        assertEquals(SessionStatus.WAITING, result);
    }

    @Test
    @DisplayName("AI_CHAT → CLOSED 合法（纯 AI 对话直接结束）")
    void aiChatToClosed_ok() {
        SessionStatus result = SessionStatus.AI_CHAT.transitionTo(SessionStatus.CLOSED);
        assertEquals(SessionStatus.CLOSED, result);
    }

    // ---- AI_CHAT 非法转换 ----

    @Test
    @DisplayName("AI_CHAT → ACTIVE 合法（自动分配跳过 WAITING）")
    void aiChatToActive_ok() {
        SessionStatus result = SessionStatus.AI_CHAT.transitionTo(SessionStatus.ACTIVE);
        assertEquals(SessionStatus.ACTIVE, result);
    }

    @Test
    @DisplayName("AI_CHAT → AI_CHAT self-transition 非法")
    void aiChatToAiChat_throws() {
        assertThrows(IllegalStateException.class,
                () -> SessionStatus.AI_CHAT.transitionTo(SessionStatus.AI_CHAT));
    }

    @Test
    @DisplayName("WAITING → AI_CHAT 非法（AI_CHAT 不是合法目标状态）")
    void waitingToAiChat_throws() {
        assertThrows(IllegalStateException.class,
                () -> SessionStatus.WAITING.transitionTo(SessionStatus.AI_CHAT));
    }

    @Test
    @DisplayName("ACTIVE → AI_CHAT 非法")
    void activeToAiChat_throws() {
        assertThrows(IllegalStateException.class,
                () -> SessionStatus.ACTIVE.transitionTo(SessionStatus.AI_CHAT));
    }

    @Test
    @DisplayName("CLOSED → AI_CHAT 非法（终态不可转换）")
    void closedToAiChat_throws() {
        assertThrows(IllegalStateException.class,
                () -> SessionStatus.CLOSED.transitionTo(SessionStatus.AI_CHAT));
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
