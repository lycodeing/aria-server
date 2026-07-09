package com.aria.conversation.infrastructure.websocket.cluster;

import com.aria.common.web.redis.RedisCacheHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WsPresenceRegistry")
class WsPresenceRegistryTest {

    @Mock RedisCacheHelper cache;
    @Mock StringRedisTemplate redis;
    @Mock SetOperations<String, String> setOps;

    private WsPresenceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WsPresenceRegistry(cache, redis);
    }

    @Test
    @DisplayName("registerVisitor 调用 cache.set 并携带 TTL")
    void registerVisitor_calls_cache_set() {
        registry.registerVisitor("sess-1", "pod-A");
        verify(cache).set(eq("ws:visitor:pod:sess-1"), eq("pod-A"), any(Duration.class));
    }

    @Test
    @DisplayName("unregisterVisitor 调用 cache.delete")
    void unregisterVisitor_calls_cache_delete() {
        registry.unregisterVisitor("sess-1");
        verify(cache).delete("ws:visitor:pod:sess-1");
    }

    @Test
    @DisplayName("getVisitorPod 调用 cache.get")
    void getVisitorPod_calls_cache_get() {
        when(cache.get("ws:visitor:pod:sess-1")).thenReturn("pod-A");
        assertThat(registry.getVisitorPod("sess-1")).isEqualTo("pod-A");
    }

    @Test
    @DisplayName("registerAgent 调用 SADD 和 cache.expire")
    void registerAgent_calls_sadd_and_expire() {
        when(redis.opsForSet()).thenReturn(setOps);
        registry.registerAgent("agent-1", "pod-A");
        verify(setOps).add("ws:agent:pods:agent-1", "pod-A");
        verify(cache).expire(eq("ws:agent:pods:agent-1"), any(Duration.class));
    }

    @Test
    @DisplayName("getAgentPods 返回集合，key 不存在时返回空集合")
    void getAgentPods_returns_empty_on_null() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.members("ws:agent:pods:agent-1")).thenReturn(null);
        assertThat(registry.getAgentPods("agent-1")).isEmpty();

        when(setOps.members("ws:agent:pods:agent-2")).thenReturn(Set.of("pod-A", "pod-B"));
        assertThat(registry.getAgentPods("agent-2")).containsExactlyInAnyOrder("pod-A", "pod-B");
    }
}
