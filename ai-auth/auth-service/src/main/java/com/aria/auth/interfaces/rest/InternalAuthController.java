package com.aria.auth.interfaces.rest;

import cn.dev33.satoken.stp.StpUtil;
import com.aria.common.web.response.R;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 内部服务间认证接口。
 * 供 knowledge-service、conversation-service 等内部服务调用，
 * 验证前端传入的 Bearer JWT Token 有效性。
 *
 * <p>鉴权由 {@code InternalSecretFilter}（common-web 自动装配）统一负责，
 * Controller 无需重复校验 {@code X-Internal-Secret} 头。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
public class InternalAuthController {

    /**
     * 验证 Bearer Token 有效性，返回用户 ID。
     *
     * @param req 包含 token 字段的请求体
     */
    @PostMapping("/token/verify")
    public R<Map<String, Object>> verify(@RequestBody VerifyRequest req) {
        Object loginId = StpUtil.getLoginIdByToken(req.getToken());
        if (loginId == null) {
            return R.fail("TOKEN_INVALID", "token 无效或已过期");
        }
        return R.ok(Map.of("valid", true, "userId", loginId.toString()));
    }

    @Data
    public static class VerifyRequest {
        @NotBlank(message = "token 不能为空")
        private String token;
    }
}
