package com.aria.auth.interfaces.rest;

import com.aria.auth.application.service.SystemConfigService;
import com.aria.common.web.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 系统配置内部接口（供内网微服务调用）。
 *
 * <p>⚠️ 此接口仅供内部服务调用，严禁对外网暴露。
 *
 * <p>鉴权由 {@code InternalSecretFilter}（common-web 自动装配）统一负责，
 * Controller 无需重复校验 {@code X-Internal-Secret} 头。
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

    private final SystemConfigService systemConfigService;

    /**
     * 内部服务接口：按 key 读取单条配置值。
     *
     * @param key 配置键
     * @return 配置值；key 不存在、已禁用时 data 为 null
     */
    @GetMapping("/value")
    public R<String> getConfigValue(@RequestParam String key) {
        String value = systemConfigService.getValue(key, null);
        return R.ok(value);
    }
}
