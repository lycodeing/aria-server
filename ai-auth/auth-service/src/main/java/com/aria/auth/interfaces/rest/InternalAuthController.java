package com.aria.auth.interfaces.rest;

import cn.dev33.satoken.stp.StpUtil;
import com.aria.auth.infrastructure.security.internal.InternalSecretVerifier;
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
 * <p>双层防护：
 * <ol>
 *   <li>Nginx 禁止外网访问 /api/v1/internal/** 路径</li>
 *   <li>X-Internal-Secret 请求头校验，防止 Nginx 配置失误时被外部调用</li>
 * </ol>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
public class InternalAuthController {

    /** 未认证：内部接口拒绝访问的 HTTP 状态语义码。 */
    private static final int FORBIDDEN_CODE = 403;

    private final InternalSecretVerifier secretVerifier;

    /**
     * 验证 Bearer Token 有效性，返回用户 ID。
     * 内部服务在处理前端请求时调用此接口完成身份确认。
     *
     * @param secret 内部服务密钥头（X-Internal-Secret）
     * @param req    包含 token 字段的请求体
     */
    @PostMapping("/token/verify")
    public R<Map<String, Object>> verify(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret,
            @RequestBody VerifyRequest req) {
        if (!secretVerifier.matches(secret)) {
            log.warn("[InternalAuth] 内部密钥校验失败，拒绝访问");
            return R.fail(FORBIDDEN_CODE, "内部接口禁止访问");
        }
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
