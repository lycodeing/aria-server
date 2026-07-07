package com.aria.auth.interfaces.rest;

import com.aria.auth.application.service.AiModelConfigService;
import com.aria.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.auth.infrastructure.security.internal.InternalSecretVerifier;
import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.ai.AiModelScopeDefaults;
import com.aria.common.web.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型配置内部接口（供 conversation/knowledge service 拉取）。
 *
 * <p>⚠️ 此接口返回已解密的 API Key，严禁对外网暴露，仅限内网微服务调用。
 *
 * <p>双层防护：
 * <ol>
 *   <li>Nginx 禁止外网访问 /internal/** 路径（第一道防线）</li>
 *   <li>X-Internal-Secret 请求头校验（代码层第二道防线，防止 Nginx 配置失误时泄露）</li>
 * </ol>
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

    /** 未认证：内部接口拒绝访问的 HTTP 状态语义码。 */
    private static final int FORBIDDEN_CODE = 403;

    /** 资源不存在：未找到激活模型配置。 */
    private static final int NOT_FOUND_CODE = 404;

    private final AiModelConfigService service;
    private final InternalSecretVerifier secretVerifier;

    /**
     * 返回当前激活（默认）的 CHAT 模型配置，api_key 为解密后明文。
     * 仅限内网调用，外网 Nginx 应配置禁止 /internal/** 路径访问。
     *
     * @param secret 内部服务密钥头（X-Internal-Secret），缺失或错误时返回 403
     */
    @GetMapping("/active")
    public R<AiModelConfig> getActive(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret) {
        if (!secretVerifier.matches(secret)) {
            log.warn("[InternalAiModel] 内部密钥校验失败，拒绝访问 /active");
            return R.fail(FORBIDDEN_CODE, "内部接口禁止访问");
        }
        AiModelConfigDO d = service.getActiveConfig();
        if (d == null) {
            return R.fail(NOT_FOUND_CODE, "未找到激活的 AI 模型配置，请在后台设置默认配置");
        }
        return R.ok(toConfig(d, AiModelScopeDefaults.CHAT));
    }

    /**
     * 返回当前激活（默认）的 EMBEDDING 模型配置，api_key 为解密后明文。
     * 供 knowledge-service 拉取向量模型配置，同样仅限内网调用。
     *
     * @param secret 内部服务密钥头（X-Internal-Secret），缺失或错误时返回 403
     */
    @GetMapping("/active-embedding")
    public R<AiModelConfig> getActiveEmbedding(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret) {
        if (!secretVerifier.matches(secret)) {
            log.warn("[InternalAiModel] 内部密钥校验失败，拒绝访问 /active-embedding");
            return R.fail(FORBIDDEN_CODE, "内部接口禁止访问");
        }
        AiModelConfigDO d = service.getActiveEmbeddingConfig();
        if (d == null) {
            return R.fail(NOT_FOUND_CODE, "未找到激活的向量模型配置，请在后台 AI 模型配置页面设置默认 EMBEDDING 配置");
        }
        return R.ok(toConfig(d, AiModelScopeDefaults.EMBEDDING));
    }

    /**
     * 返回当前激活（默认）的 ROUTER 模型配置，api_key 为解密后明文。
     * 供 conversation-service 拉取域路由模型配置，仅限内网调用。
     *
     * @param secret 内部服务密钥头（X-Internal-Secret），缺失或错误时返回 403
     */
    @GetMapping("/active-router")
    public R<AiModelConfig> getActiveRouter(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret) {
        if (!secretVerifier.matches(secret)) {
            log.warn("[InternalAiModel] 内部密钥校验失败，拒绝访问 /active-router");
            return R.fail(FORBIDDEN_CODE, "内部接口禁止访问");
        }
        AiModelConfigDO d = service.getActiveRouterConfig();
        if (d == null) {
            return R.fail(NOT_FOUND_CODE, "未找到激活的 ROUTER 模型配置，请在后台 AI 模型配置页面设置默认 ROUTER 配置");
        }
        return R.ok(toConfig(d, AiModelScopeDefaults.ROUTER));
    }

    // ---- 内部工具 ----

    /**
     * 将 DO 转换为 AiModelConfig，使用 {@link AiModelScopeDefaults} 填充 null 字段。
     * 缺省值统一由枚举管理，避免服务端与客户端各自硬编码魔法值。
     */
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
