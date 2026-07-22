package com.aria.auth.interfaces.rest;

import com.aria.auth.application.service.AiModelConfigService;
import com.aria.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelScopeDefaults;
import com.aria.common.web.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型配置内部接口（供 conversation/knowledge service 拉取）。
 *
 * <p>⚠️ 此接口返回已解密的 API Key，严禁对外网暴露，仅限内网微服务调用。
 *
 * <p>鉴权由 {@code InternalSecretFilter}（common-web 自动装配）统一负责，
 * 在 Filter 层校验 {@code X-Internal-Secret} 请求头，Controller 无需重复校验。
 *
 * <pre>
 * GET /internal/ai-models/active           返回当前默认 CHAT 配置（api_key 已解密明文）
 * GET /internal/ai-models/active-embedding 返回当前默认 EMBEDDING 配置（api_key 已解密明文）
 * GET /internal/ai-models/active-router    返回当前默认 ROUTER 配置（api_key 已解密明文）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/internal/ai-models")
@RequiredArgsConstructor
public class InternalAiModelController {

    /** 资源不存在：未找到激活模型配置。 */
    private static final int NOT_FOUND_CODE = 404;

    private final AiModelConfigService service;

    /**
     * 返回当前激活（默认）的 CHAT 模型配置，api_key 为解密后明文。
     */
    @GetMapping("/active")
    public R<AiModelConfig> getActive() {
        AiModelConfigDO d = service.getActiveConfig();
        if (d == null) {
            return R.fail(NOT_FOUND_CODE, "未找到激活的 AI 模型配置，请在后台设置默认配置");
        }
        return R.ok(toConfig(d, AiModelScopeDefaults.CHAT));
    }

    /**
     * 返回当前激活（默认）的 EMBEDDING 模型配置，api_key 为解密后明文。
     */
    @GetMapping("/active-embedding")
    public R<AiModelConfig> getActiveEmbedding() {
        AiModelConfigDO d = service.getActiveEmbeddingConfig();
        if (d == null) {
            return R.fail(NOT_FOUND_CODE, "未找到激活的向量模型配置，请在后台 AI 模型配置页面设置默认 EMBEDDING 配置");
        }
        return R.ok(toConfig(d, AiModelScopeDefaults.EMBEDDING));
    }

    /**
     * 返回当前激活（默认）的 ROUTER 模型配置，api_key 为解密后明文。
     */
    @GetMapping("/active-router")
    public R<AiModelConfig> getActiveRouter() {
        AiModelConfigDO d = service.getActiveRouterConfig();
        if (d == null) {
            return R.fail(NOT_FOUND_CODE, "未找到激活的 ROUTER 模型配置，请在后台 AI 模型配置页面设置默认 ROUTER 配置");
        }
        return R.ok(toConfig(d, AiModelScopeDefaults.ROUTER));
    }

    // ---- 内部工具 ----

    private AiModelConfig toConfig(AiModelConfigDO do_, AiModelScopeDefaults defaults) {
        return new AiModelConfig(
                do_.getId(),
                do_.getName(),
                do_.getProvider(),
                do_.getApiProtocol(),
                do_.getBaseUrl(),
                service.decryptApiKey(do_.getApiKeyEnc()),
                do_.getModelName(),
                do_.getTemperature() != null ? do_.getTemperature().doubleValue() : defaults.defaultTemperature(),
                do_.getMaxTokens() != null ? do_.getMaxTokens() : defaults.defaultMaxTokens(),
                do_.getTimeoutSec() != null ? do_.getTimeoutSec() : defaults.defaultTimeoutSec()
        );
    }
}
