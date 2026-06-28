package com.aidevplatform.customerservice.auth.interfaces.rest;

import cn.dev33.satoken.stp.StpUtil;
import com.aidevplatform.common.web.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部服务间认证接口。
 * 供 knowledge-service、conversation-service 等内部服务调用，
 * 验证前端传入的 Bearer JWT Token 有效性。
 * 不依赖 ApiKey 机制（ApiKey 为平台级功能，不属于客服系统）。
 */
@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
public class InternalAuthController {

    /**
     * 验证 Bearer Token 有效性，返回用户 ID。
     * 内部服务在处理前端请求时调用此接口完成身份确认。
     *
     * @param req 包含 token 字段的请求体
     */
    @PostMapping("/token/verify")
    public R<Map<String, Object>> verify(@RequestBody Map<String, String> req) {
        String token = req.get("token");
        if (token == null || token.isBlank()) {
            return R.fail("TOKEN_MISSING", "token 不能为空");
        }
        // 使用 Sa-Token 验证 JWT token 有效性
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            return R.fail("TOKEN_INVALID", "token 无效或已过期");
        }
        return R.ok(Map.of(
                "valid", true,
                "userId", loginId.toString()
        ));
    }
}