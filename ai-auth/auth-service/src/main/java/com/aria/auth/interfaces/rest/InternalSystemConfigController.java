package com.aria.auth.interfaces.rest;

import com.aria.auth.application.service.SystemConfigService;
import com.aria.auth.infrastructure.security.internal.InternalSecretVerifier;
import com.aria.common.web.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 系统配置内部接口（供内网微服务调用）。
 *
 * <p>⚠️ 此接口仅供内部服务调用，严禁对外网暴露。
 *
 * <p>双层防护：
 * <ol>
 *   <li>Nginx 禁止外网访问 /internal/** 路径（第一道防线）</li>
 *   <li>X-Internal-Secret 请求头校验（代码层第二道防线，防止 Nginx 配置失误时被外部调用）</li>
 * </ol>
 *
 * <pre>
 * GET /internal/system-config/value?key={key}  按 key 读取单条配置值
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/internal/system-config")
@RequiredArgsConstructor
public class InternalSystemConfigController {

    /** 未认证：内部接口拒绝访问的 HTTP 状态语义码。 */
    private static final int FORBIDDEN_CODE = 403;

    private final SystemConfigService systemConfigService;
    private final InternalSecretVerifier secretVerifier;

    /**
     * 内部服务接口：按 key 读取单条配置值。
     * 仅供内网微服务调用（需要 X-Internal-Secret 头），不走 SaToken 鉴权。
     *
     * @param secret 内部服务密钥头（X-Internal-Secret），缺失或错误时返回 403
     * @param key    配置键
     * @return 配置值；key 不存在、已禁用时 data 为 null
     */
    @GetMapping("/value")
    public R<String> getConfigValue(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret,
            @RequestParam String key) {
        if (!secretVerifier.matches(secret)) {
            log.warn("[InternalSystemConfig] 内部密钥校验失败，拒绝访问 /internal/system-config/value key={}", key);
            return R.fail(FORBIDDEN_CODE, "内部接口禁止访问");
        }
        String value = systemConfigService.getValue(key, null);
        return R.ok(value);
    }
}
