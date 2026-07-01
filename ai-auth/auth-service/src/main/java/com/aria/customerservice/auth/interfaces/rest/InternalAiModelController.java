package com.aria.customerservice.auth.interfaces.rest;

import com.aria.customerservice.auth.application.service.AiModelConfigService;
import com.aria.customerservice.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.common.web.ai.AiModelConfig;
import com.aria.common.web.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型配置内部接口（不鉴权，供 conversation/knowledge service 拉取）。
 *
 * <p>⚠️ 此接口返回已解密的 API Key，严禁对外网暴露，仅限内网微服务调用。
 *
 * <pre>
 * GET /internal/ai-models/active  返回当前默认配置（api_key 已解密明文）
 * </pre>
 */
@RestController
@RequestMapping("/internal/ai-models")
@RequiredArgsConstructor
public class InternalAiModelController {

    private final AiModelConfigService service;

    /**
     * 返回当前激活（默认）的 AI 模型配置，api_key 为解密后明文。
     * 仅限内网调用，外网 Nginx 应配置禁止 /internal/** 路径访问。
     */
    @GetMapping("/active")
    public R<AiModelConfig> getActive() {
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
                do_.getMaxTokens() != null ? do_.getMaxTokens() : 2048,
                do_.getTimeoutSec() != null ? do_.getTimeoutSec() : 60
        );
        return R.ok(config);
    }
}
