package com.aria.common.web.ai;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.sdk.auth.AuthClient;
import com.aria.sdk.auth.model.AiModelConfigDTO;
import com.aria.sdk.auth.model.ModelScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RemoteAiModelConfigProvider} 单元测试。
 *
 * <p>验证缓存命中/未命中路径、DTO → 领域对象映射、Pub/Sub 失效回调、TTL 传递。
 *
 * @author lycodeing
 * @since 2026-07
 */
class RemoteAiModelConfigProviderTest {

    private RedisCacheHelper cache;
    private AuthClient authClient;
    private RemoteAiModelConfigProvider provider;

    @BeforeEach
    void setUp() {
        cache = mock(RedisCacheHelper.class);
        authClient = mock(AuthClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.data.redis.listener.RedisMessageListenerContainer> op =
                mock(ObjectProvider.class);
        provider = new RemoteAiModelConfigProvider(cache, authClient, op);
    }

    @Test
    @DisplayName("缓存未命中时触发 AuthClient 拉取 CHAT 配置并映射默认值")
    void chatFetchesFromRemoteWhenCacheMiss() {
        when(cache.getOrLoad(any(), eq(AiModelConfig.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        AiModelConfigDTO dto = new AiModelConfigDTO(
                7L, "gpt-4o", "openai", null, "https://x", "sk", "gpt-4o",
                null, null, null);
        when(authClient.getActiveModel(ModelScope.CHAT)).thenReturn(dto);

        AiModelConfig cfg = provider.getActive();

        assertThat(cfg.id()).isEqualTo(7L);
        // apiProtocol 缺失时兜底为 OPENAI_COMPATIBLE
        assertThat(cfg.apiProtocol()).isEqualTo("OPENAI_COMPATIBLE");
        // CHAT 场景默认值：温度 0.7 / 2048 tokens / 60s
        assertThat(cfg.temperature()).isEqualTo(0.7D);
        assertThat(cfg.maxTokens()).isEqualTo(2048);
        assertThat(cfg.timeoutSec()).isEqualTo(60);
        verify(authClient, times(1)).getActiveModel(ModelScope.CHAT);
    }

    @Test
    @DisplayName("EMBEDDING 场景使用不同默认值")
    void embeddingUsesDifferentDefaults() {
        when(cache.getOrLoad(any(), eq(AiModelConfig.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        when(authClient.getActiveModel(ModelScope.EMBEDDING)).thenReturn(
                new AiModelConfigDTO(1L, "bge", "x", "OPENAI_COMPATIBLE", "http://x", "k",
                        "bge-m3", null, null, null));

        AiModelConfig cfg = provider.getActiveEmbedding();

        assertThat(cfg.temperature()).isEqualTo(0.0D);
        assertThat(cfg.maxTokens()).isEqualTo(0);
        assertThat(cfg.timeoutSec()).isEqualTo(30);
    }

    @Test
    @DisplayName("ROUTER 场景使用低延迟默认值")
    void routerUsesLowLatencyDefaults() {
        when(cache.getOrLoad(any(), eq(AiModelConfig.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        when(authClient.getActiveModel(ModelScope.ROUTER)).thenReturn(
                new AiModelConfigDTO(2L, "r", "x", "OPENAI_COMPATIBLE", "http://x", "k",
                        "router-mini", null, null, null));

        AiModelConfig cfg = provider.getActiveRouter();

        assertThat(cfg.temperature()).isEqualTo(0.0D);
        assertThat(cfg.maxTokens()).isEqualTo(32);
        assertThat(cfg.timeoutSec()).isEqualTo(5);
    }

    @Test
    @DisplayName("缓存命中时不发起远端调用")
    void cacheHitSkipsRemote() {
        AiModelConfig cached = new AiModelConfig(1L, "n", "p", "OPENAI_COMPATIBLE",
                "http://x", "k", "m", 0.7D, 2048, 60);
        when(cache.getOrLoad(any(), eq(AiModelConfig.class), any(), any()))
                .thenReturn(cached);

        AiModelConfig cfg = provider.getActive();

        assertThat(cfg).isSameAs(cached);
        verify(authClient, never()).getActiveModel(any());
    }

    @Test
    @DisplayName("invalidate 分别清理对应缓存键")
    void invalidateClearsScopedCache() {
        provider.invalidate();
        verify(cache).delete("aria:ai:model:active");

        provider.invalidateEmbedding();
        verify(cache).delete("aria:ai:model:embedding:active");

        provider.invalidateRouter();
        verify(cache).delete("aria:ai:model:router:active");
    }

    @Test
    @DisplayName("onMessage 触发三份缓存全部失效")
    void onMessageInvalidatesAll() {
        provider.onMessage(null, null);

        verify(cache).delete("aria:ai:model:active");
        verify(cache).delete("aria:ai:model:embedding:active");
        verify(cache).delete("aria:ai:model:router:active");
    }

    @Test
    @DisplayName("服务端返回的值优先于本地默认值")
    void serverValuesOverrideDefaults() {
        when(cache.getOrLoad(any(), eq(AiModelConfig.class), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        AiModelConfigDTO dto = new AiModelConfigDTO(
                3L, "n", "p", "ANTHROPIC", "http://x", "k", "m",
                0.3D, 4096, 90);
        when(authClient.getActiveModel(ModelScope.CHAT)).thenReturn(dto);

        AiModelConfig cfg = provider.getActive();

        assertThat(cfg.apiProtocol()).isEqualTo("ANTHROPIC");
        assertThat(cfg.temperature()).isEqualTo(0.3D);
        assertThat(cfg.maxTokens()).isEqualTo(4096);
        assertThat(cfg.timeoutSec()).isEqualTo(90);
    }

    @Test
    @DisplayName("缓存 TTL 传递为 5 分钟")
    void cacheTtlIsFiveMinutes() {
        when(cache.getOrLoad(any(), any(), any(), any())).thenReturn(null);
        provider.getActive();
        verify(cache).getOrLoad(eq("aria:ai:model:active"), eq(AiModelConfig.class),
                eq(Duration.ofMinutes(5)), any());
    }
}
