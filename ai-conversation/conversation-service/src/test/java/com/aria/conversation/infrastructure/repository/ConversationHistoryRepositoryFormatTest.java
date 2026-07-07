package com.aria.conversation.infrastructure.repository;

import com.aria.common.core.util.JsonUtils;
import com.aria.conversation.domain.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只验证 Redis List 反序列化格式兼容性（新 JSON-per-slot / 旧 4 元组 / 旧 3 元组）。
 * 不启动 Redis，通过反射直接构造 repository 并调用 {@code parseMessages}。
 */
class ConversationHistoryRepositoryFormatTest {

    private final ConversationHistoryRepository repo = newRepositoryStub();

    private static ConversationHistoryRepository newRepositoryStub() {
        try {
            Constructor<ConversationHistoryRepository> c = ConversationHistoryRepository.class
                    .getDeclaredConstructor(
                            com.aria.common.web.redis.RedisCacheHelper.class,
                            com.aria.common.web.redis.RedisLockHelper.class,
                            com.aria.conversation.infrastructure.mq.ConversationMessagePublisher.class,
                            com.aria.conversation.infrastructure.persistence.mapper.ConversationMessageMapper.class);
            c.setAccessible(true);
            return c.newInstance(null, null, null, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法构造 ConversationHistoryRepository", e);
        }
    }

    @Test
    void parseMessages_newJsonSlotFormat() {
        ConversationMessage m1 = ConversationMessage.of("user", "hi", 1L);
        ConversationMessage m2 = new ConversationMessage(
                "assistant", null, 2L, 1730000000000L, null, null,
                List.of(new ConversationMessage.ToolCall("call_1", "get_weather", "{}")));

        List<String> raw = List.of(JsonUtils.toJsonString(m1), JsonUtils.toJsonString(m2));
        List<ConversationMessage> parsed = repo.parseMessages(raw);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).role()).isEqualTo("user");
        assertThat(parsed.get(0).content()).isEqualTo("hi");
        assertThat(parsed.get(1).toolCalls()).hasSize(1);
        assertThat(parsed.get(1).toolCalls().get(0).id()).isEqualTo("call_1");
    }

    @Test
    void parseMessages_legacyQuadFormat() {
        List<String> raw = List.of(
                "user", "hello", "1", "1730000000000",
                "assistant", "hi", "2", "1730000000100");
        List<ConversationMessage> parsed = repo.parseMessages(raw);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).role()).isEqualTo("user");
        assertThat(parsed.get(0).seq()).isEqualTo(1L);
        assertThat(parsed.get(0).timestamp()).isEqualTo(1730000000000L);
        assertThat(parsed.get(1).content()).isEqualTo("hi");
    }

    @Test
    void parseMessages_legacyTripleFormat() {
        List<String> raw = List.of(
                "user", "hello", "1",
                "assistant", "hi", "2");
        List<ConversationMessage> parsed = repo.parseMessages(raw);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).timestamp()).isNull();
        assertThat(parsed.get(1).seq()).isEqualTo(2L);
    }

    @Test
    void parseMessages_emptyReturnsEmpty() {
        assertThat(repo.parseMessages(List.of())).isEmpty();
        assertThat(repo.parseMessages(null)).isEmpty();
    }

    @Test
    void parseMessages_corruptedJsonSlotIsSkipped() {
        List<String> raw = List.of(
                JsonUtils.toJsonString(ConversationMessage.of("user", "ok", 1L)),
                "{not-a-valid-json",
                JsonUtils.toJsonString(ConversationMessage.of("assistant", "still ok", 2L)));

        List<ConversationMessage> parsed = repo.parseMessages(raw);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).content()).isEqualTo("ok");
        assertThat(parsed.get(1).content()).isEqualTo("still ok");
    }
}
