package com.aria.conversation.infrastructure.dit.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingSlotRepository")
class PendingSlotRepositoryTest {

    @Mock private RedisCacheHelper cache;
    private PendingSlotRepository repo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = new PendingSlotRepository(cache, objectMapper);
    }

    @Test
    @DisplayName("save: 序列化后调用 cache.set")
    void save_callsCacheSet() {
        PendingSlotState state = new PendingSlotState(
                "s1", "ecommerce", "query_order", "order_id",
                "MISSING", null, Map.of(), 0);

        repo.save(state);

        verify(cache).set(eq("dit:pending:s1"), anyString(), any());
    }

    @Test
    @DisplayName("find: cache miss 返回 empty")
    void find_cacheMiss_returnsEmpty() {
        when(cache.get("dit:pending:s1")).thenReturn(null);
        assertTrue(repo.find("s1").isEmpty());
    }

    @Test
    @DisplayName("find: cache hit 反序列化返回 state")
    void find_cacheHit_returnsState() throws Exception {
        PendingSlotState state = new PendingSlotState(
                "s1", "ecommerce", "query_order", "order_id",
                "MISSING", null, Map.of(), 1);
        // 用同一个 objectMapper 序列化，保证格式一致
        String json = objectMapper.writeValueAsString(state);
        when(cache.get("dit:pending:s1")).thenReturn(json);

        Optional<PendingSlotState> result = repo.find("s1");

        assertTrue(result.isPresent());
        assertEquals("order_id", result.get().getPendingSlot());
        assertEquals(1, result.get().getRetryCount());
    }

    @Test
    @DisplayName("find: 反序列化失败时删缓存并返回 empty")
    void find_deserializeFails_deletesAndReturnsEmpty() {
        when(cache.get("dit:pending:s1")).thenReturn("not-valid-json{{{");

        Optional<PendingSlotState> result = repo.find("s1");

        assertTrue(result.isEmpty());
        verify(cache).delete("dit:pending:s1");
    }

    @Test
    @DisplayName("delete: 调用 cache.delete")
    void delete_callsCacheDelete() {
        repo.delete("s1");
        verify(cache).delete("dit:pending:s1");
    }

    @Test
    @DisplayName("hasPending: cache miss 返回 false")
    void hasPending_false_whenNoCache() {
        when(cache.get(anyString())).thenReturn(null);
        assertFalse(repo.hasPending("s1"));
    }

    @Test
    @DisplayName("hasPending: cache hit 返回 true")
    void hasPending_true_whenCacheHit() throws Exception {
        PendingSlotState state = new PendingSlotState(
                "s1", "d", "i", "slot", "MISSING", null, Map.of(), 0);
        when(cache.get("dit:pending:s1")).thenReturn(objectMapper.writeValueAsString(state));

        assertTrue(repo.hasPending("s1"));
    }
}
