package com.aria.common.web.ai;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.sdk.auth.AuthClient;
import com.aria.sdk.auth.model.AiModelConfigDTO;
import com.aria.sdk.auth.model.ModelScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

/**
 * 远程 AI 模型配置提供者。
 *
 * <p>职责：
 * <ol>
 *   <li>缓存治理：CHAT / EMBEDDING / ROUTER 三份配置各自 5 分钟 TTL。</li>
 *   <li>事件监听：订阅 {@code aria:config:ai-changed} Pub/Sub 主题，收到变更后主动清缓存。</li>
 * </ol>
 *
 * <p>HTTP 协议细节（URL 路径、{@code X-Internal-Secret} 头、{@code R<T>} 响应解析）
 * 下沉到 {@link AuthClient}；本类只做业务缓存与事件桥接。
 *
 * <p>降级策略：auth-service 不可用时由 {@link AuthClient} 抛
 * {@link com.aria.sdk.auth.exception.AuthClientException}，本类不吞异常，
 * 由调用方通过降级链兜底。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnBean(AuthClient.class)
public class RemoteAiModelConfigProvider implements AiModelConfigProvider, MessageListener {

    private static final String CHAT_CACHE_KEY      = "aria:ai:model:active";
    private static final String EMBEDDING_CACHE_KEY = "aria:ai:model:embedding:active";
    private static final String ROUTER_CACHE_KEY    = "aria:ai:model:router:active";
    private static final Duration CACHE_TTL         = Duration.ofMinutes(5);
    private static final String PUBSUB_TOPIC        = "aria:config:ai-changed";

    private final RedisCacheHelper cache;
    private final AuthClient       authClient;

    public RemoteAiModelConfigProvider(
            RedisCacheHelper cache,
            AuthClient authClient,
            ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        this.cache      = cache;
        this.authClient = authClient;
        // 容器存在时注册 Pub/Sub 监听；未配置时跳过，配置变更需等待缓存 TTL 自然过期
        containerProvider.ifAvailable(c -> c.addMessageListener(this, new ChannelTopic(PUBSUB_TOPIC)));
    }

    // ---- CHAT 配置 ----

    @Override
    public AiModelConfig getActive() {
        return cache.getOrLoad(CHAT_CACHE_KEY, AiModelConfig.class, CACHE_TTL,
                () -> loadFromRemote(ModelScope.CHAT, AiModelScopeDefaults.CHAT));
    }

    @Override
    public void invalidate() {
        cache.delete(CHAT_CACHE_KEY);
        log.info("[AiConfig] CHAT 配置缓存已失效，下次请求将重新拉取");
    }

    // ---- EMBEDDING 配置 ----

    @Override
    public AiModelConfig getActiveEmbedding() {
        return cache.getOrLoad(EMBEDDING_CACHE_KEY, AiModelConfig.class, CACHE_TTL,
                () -> loadFromRemote(ModelScope.EMBEDDING, AiModelScopeDefaults.EMBEDDING));
    }

    @Override
    public void invalidateEmbedding() {
        cache.delete(EMBEDDING_CACHE_KEY);
        log.info("[AiConfig] EMBEDDING 配置缓存已失效，下次请求将重新拉取");
    }

    // ---- ROUTER 配置 ----

    @Override
    public AiModelConfig getActiveRouter() {
        return cache.getOrLoad(ROUTER_CACHE_KEY, AiModelConfig.class, CACHE_TTL,
                () -> loadFromRemote(ModelScope.ROUTER, AiModelScopeDefaults.ROUTER));
    }

    @Override
    public void invalidateRouter() {
        cache.delete(ROUTER_CACHE_KEY);
        log.info("[AiConfig] ROUTER 配置缓存已失效，下次请求将重新拉取");
    }

    // ---- Redis Pub/Sub ----

    /** 配置变更通知：同时失效 CHAT + EMBEDDING + ROUTER 三个缓存。 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("[AiConfig] 收到配置变更通知，清除 CHAT + EMBEDDING + ROUTER 缓存");
        invalidate();
        invalidateEmbedding();
        invalidateRouter();
    }

    // ---- 内部工具 ----

    /** 未指定协议时的默认协议标识。 */
    private static final String DEFAULT_API_PROTOCOL = "OPENAI_COMPATIBLE";

    /**
     * 通过 {@link AuthClient} 拉取远端配置并映射为业务领域类型。
     *
     * <p>DTO 与领域对象刻意分离，允许两端字段独立演进；缺省值统一从
     * {@link AiModelScopeDefaults} 读取，防止魔法值散落两处。
     */
    private AiModelConfig loadFromRemote(ModelScope scope, AiModelScopeDefaults defaults) {
        AiModelConfigDTO dto = authClient.getActiveModel(scope);
        log.debug("[AiConfig] 已从 auth-service 拉取 {} 配置，model={}", scope, dto.modelName());
        return new AiModelConfig(
                dto.id(),
                dto.name(),
                dto.provider(),
                dto.apiProtocol() != null ? dto.apiProtocol() : DEFAULT_API_PROTOCOL,
                dto.baseUrl(),
                dto.apiKey(),
                dto.modelName(),
                dto.temperature() != null ? dto.temperature() : defaults.defaultTemperature(),
                dto.maxTokens()   != null ? dto.maxTokens()   : defaults.defaultMaxTokens(),
                dto.timeoutSec()  != null ? dto.timeoutSec()  : defaults.defaultTimeoutSec()
        );
    }
}
