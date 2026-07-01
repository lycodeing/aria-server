package com.aria.conversation.interfaces.rest;

import com.aria.common.web.response.R;
import com.aria.conversation.application.service.VisitorAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 访客身份验证接口（手机号 + 短信验证码）。
 *
 * <p>访客公开接口，允许任意源访问（chat-widget 内嵌至任意站点）。
 *
 * <p>接口列表：
 * <pre>
 *   POST /api/v1/chat/auth/sms/send   — 发送短信验证码
 *   POST /api/v1/chat/auth/sms/verify — 校验验证码，成功返回访客 token
 * </pre>
 */
@Validated
@RestController
@RequestMapping("/api/v1/chat/auth/sms")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class VisitorAuthController {

    private final VisitorAuthService visitorAuthService;

    /**
     * 发送短信验证码。
     *
     * <p>60s 内同一手机号只能发送 1 次；验证码有效期 5 分钟。
     *
     * @param req 请求体，包含手机号
     * @return 成功时返回空 data（前端开启 60s 倒计时）
     */
    @PostMapping("/send")
    public R<Void> send(@RequestBody @Valid SendCodeRequest req) {
        visitorAuthService.sendCode(req.getPhone());
        return R.ok();
    }

    /**
     * 校验验证码，返回访客 token。
     *
     * <p>验证成功后 token 有效期 2 小时，可用于后续需要身份核验的访客接口。
     *
     * @param req 请求体，包含手机号和 6 位验证码
     * @return {@code { token: "..." }}
     */
    @PostMapping("/verify")
    public R<Map<String, String>> verify(@RequestBody @Valid VerifyCodeRequest req) {
        String token = visitorAuthService.verifyCode(req.getPhone(), req.getCode());
        return R.ok(Map.of("token", token));
    }

    // ---- 请求体 DTO ----

    @Data
    public static class SendCodeRequest {
        /** 11 位手机号 */
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;
    }

    @Data
    public static class VerifyCodeRequest {
        /** 11 位手机号 */
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;

        /** 6 位数字验证码 */
        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "^\\d{6}$", message = "验证码必须为 6 位数字")
        private String code;
    }
}
