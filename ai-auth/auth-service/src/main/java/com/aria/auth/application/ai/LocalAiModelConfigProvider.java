package com.aria.auth.application.ai;

import com.aria.auth.application.service.AiModelConfigService;
import com.aria.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelConfigProvider;
import com.aria.common.web.ai.AiModelScopeDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * auth-service 自身使用的 {@link AiModelConfigProvider} 本地实现。
 *
 * <p>{@link AiModelConfigProvider} 属于应用层暴露的 SPI，此实现直接调用
 * {@link AiModelConfigService} 查询数据库并复用其解密逻辑，避免 auth-service
 * 通过 HTTP 调用自身 {@code /internal/ai-models/**} 造成自调环路。
 *
 * <p>DDD 分层：本 Provider 依赖应用服务与领域缺省值 ({@link AiModelScopeDefaults})，
 * 放置于 {@code application/ai} 而非 {@code infrastructure/ai}，遵守
 * "infrastructure 不反向依赖 application" 的分层铁律。
 *
 * <p>标注 {@link Primary} 是为了在 {@code aria.auth.client.enabled} 被误设为 {@code true}
 * 时仍保证本地实现优先生效；正常情况下配合 {@code application.yml} 关闭 AuthClient 装配，
 * {@code RemoteAiModelConfigProvider} 也不会被创建。
 *
 * <p>缓存与 Pub/Sub 主动失效由 conversation-service / knowledge-service 的
 * {@code RemoteAiModelConfigProvider} 负责；本类只是本地兜底，故三个 {@code invalidateXxx()}
 * 均为 no-op（本地无缓存需要清理）。
 *
 * @author lycodeing
 * @since 2026-07
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class LocalAiModelConfigProvider implements AiModelConfigProvider {

    private final AiModelConfigService service;

    @Override
    public AiModelConfig getActive() {
        AiModelConfigDO active = service.getActiveConfig();
        if (active == null) {
            throw new IllegalStateException("本地未找到激活的 CHAT 模型配置，请在后台设置默认配置");
        }
        return toConfig(active, AiModelScopeDefaults.CHAT);
    }

    @Override
    public AiModelConfig getActiveEmbedding() {
        AiModelConfigDO active = service.getActiveEmbeddingConfig();
        if (active == null) {
            throw new IllegalStateException("本地未找到激活的 EMBEDDING 模型配置，请在后台设置默认 EMBEDDING 配置");
        }
        return toConfig(active, AiModelScopeDefaults.EMBEDDING);
    }

    @Override
    public AiModelConfig getActiveRouter() {
        AiModelConfigDO active = service.getActiveRouterConfig();
        if (active == null) {
            throw new IllegalStateException("本地未找到激活的 ROUTER 模型配置，请在后台设置默认 ROUTER 配置");
        }
        return toConfig(active, AiModelScopeDefaults.ROUTER);
    }

    @Override
    public void invalidate() {
        // 本地实现无本地缓存；配置变更由 AiModelConfigService 广播 Pub/Sub，
        // 下游服务（conversation/knowledge）的 RemoteAiModelConfigProvider 会自行失效。
    }

    @Override
    public void invalidateEmbedding() {
        // 同 {@link #invalidate()}，本地无缓存需要清理。
    }

    @Override
    public void invalidateRouter() {
        // 同 {@link #invalidate()}，本地无缓存需要清理。
    }

    // ---- 内部工具 ----

    /**
     * 将 DO 映射为业务领域对象；缺省值统一从 {@link AiModelScopeDefaults} 读取。
     */
    private AiModelConfig toConfig(AiModelConfigDO d, AiModelScopeDefaults defaults) {
        return new AiModelConfig(
                d.getId(),
                d.getName(),
                d.getProvider(),
                d.getApiProtocol(),
                d.getBaseUrl(),
                service.decryptApiKey(d.getApiKeyEnc()),
                d.getModelName(),
                d.getTemperature() != null ? d.getTemperature().doubleValue() : defaults.defaultTemperature(),
                d.getMaxTokens() != null ? d.getMaxTokens() : defaults.defaultMaxTokens(),
                d.getTimeoutSec() != null ? d.getTimeoutSec() : defaults.defaultTimeoutSec()
        );
    }
}
