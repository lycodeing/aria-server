package com.aria.conversation.infrastructure.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChatWebSocketHandler} sessionId 格式校验单元测试。
 *
 * <p>通过反射读取 SESSION_ID_PATTERN 常量，验证合法/非法 sessionId 的匹配行为。
 * 无需启动 Spring 容器，纯逻辑测试。
 */
@DisplayName("ChatWebSocketHandler sessionId 格式校验")
class ChatWebSocketHandlerSessionIdTest {

    /** 从 handler 读取私有静态正则常量，保证测试与实现用同一个正则 */
    private static final Pattern SESSION_ID_PATTERN = getPattern();

    private static Pattern getPattern() {
        try {
            Field f = ChatWebSocketHandler.class.getDeclaredField("SESSION_ID_PATTERN");
            f.setAccessible(true);
            return (Pattern) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("无法读取 SESSION_ID_PATTERN", e);
        }
    }

    // ---- 合法 sessionId ----

    @ParameterizedTest(name = "合法 sessionId: [{0}]")
    @ValueSource(strings = {
            "abc123",
            "guest-abcdef1234567890",
            "SESSION_001",
            "a",                          // 最短：1 字符
            "a1b2c3d4e5f6g7h8i9j0klmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ12"  // 64 字符
    })
    @DisplayName("合法格式应通过校验")
    void validSessionId_matches(String sessionId) {
        assertTrue(SESSION_ID_PATTERN.matcher(sessionId).matches(),
                "期望通过校验: " + sessionId);
    }

    // ---- 非法 sessionId ----

    @ParameterizedTest(name = "非法 sessionId: [{0}]")
    @ValueSource(strings = {
            "",                           // 空字符串（DEFAULT_SESSION_ID）
            " ",                          // 空格
            "hello world",                // 含空格
            "session/../../etc",          // 路径穿越
            "session<script>",            // XSS 尝试
            "a1b2c3d4e5f6g7h8i9j0klmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ123"  // 65 字符（超长）
    })
    @DisplayName("非法格式应被拒绝")
    void invalidSessionId_notMatches(String sessionId) {
        assertFalse(SESSION_ID_PATTERN.matcher(sessionId).matches(),
                "期望被拒绝: " + sessionId);
    }
}
