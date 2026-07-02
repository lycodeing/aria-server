package com.aria.auth.interfaces.rest;

import com.aria.auth.application.service.AiModelConfigService;
import com.aria.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * GET /internal/ai-models/active  返回当前默认配置（api_key 已解密明文）
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/internal/ai-models")
@RequiredArgsConstructor
public class InternalAiModelController {

    private final AiModelConfigService service;

    /**
     * 内部共享密钥，通过环境变量注入。
     * 调用方（conversation-service/knowledge-service）请求时须在 X-Internal-Secret 头携带此值。
     */
    @Value("${aria.internal.secret}")
    private String internalSecret;

    /**
     * 返回当前激活（默认）的 AI 模型配置，api_key 为解密后明文。
     * 仅限内网调用，外网 Nginx 应配置禁止 /internal/** 路径访问。
     *
     * @param secret 内部服务密钥头（X-Internal-Secret），缺失或错误时返回 403
     */
    @GetMapping("/active")
    public R<AiModelConfig> getActive(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret) {
        if (internalSecret == null || !internalSecret.equals(secret)) {
            log.warn("[InternalAiModel] 内部密钥校验失败，拒绝访问");
            return R.fail(403, "内部接口禁止访问");
        }
        AiModelConfigDO do_ = service.getActiveConfig();
        if (do_ == null) {
            return R.fail(404, "未找到激活的 AI 模型配置，请在后台设置默认配置");
        }
        AiModelConfig config = new AiModelConfig(
                do_.getId(),
                do_.getName(),
                do_.getProvider(),
                do_.getApiProtocol(),
                do_.getBaseUrl(),
                service.decryptApiKey(do_.getApiKeyEnc()),
                do_.getModelName(),
                do_.getTemperature() != null ? do_.getTemperature().doubleValue() : 0.7,
                do_.getMaxTokens()   != null ? do_.getMaxTokens()                  : 2048,
                do_.getTimeoutSec()  != null ? do_.getTimeoutSec()                 : 60
        );
        return R.ok(config);
    }
}
