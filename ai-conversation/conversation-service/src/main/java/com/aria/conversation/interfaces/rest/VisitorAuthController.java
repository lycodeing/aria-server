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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 访客身份验证接口（手机号 + 短信验证码）。
 *
 * <p>访客公开接口，允许任意源访问（chat-widget 内嵌至任意站点）。
 *
 * <p>接口列表：
 * <pre>
 *   POST /api/v1/chat/auth/sms/send   — 发送短信验证码
 *   POST /api/v1/chat/auth/sms/verify — 校验验证码，成功返回访客 token（可选绑定 session）
 *   GET  /api/v1/chat/auth/state      — 按 sessionId 回查当前认证状态（刷新恢复）
 * </pre>
 */
@Validated
@RestController
@RequestMapping("/api/v1/chat/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class VisitorAuthController {

    /** sessionId 格式校验：与 ChatController 保持一致，防止 Redis key 注入。 */
    private static final java.util.regex.Pattern SESSION_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    private final VisitorAuthService visitorAuthService;

    /**
     * 发送短信验证码。
     *
     * <p>60s 内同一手机号只能发送 1 次；验证码有效期 5 分钟。
     */
    @PostMapping("/sms/send")
    public R<Void> send(@RequestBody @Valid SendCodeRequest req) {
        visitorAuthService.sendCode(req.getPhone());
        return R.ok();
    }

    /**
     * 校验验证码，返回访客 token。
     *
     * <p>验证成功后 token 有效期 2 小时；若请求体中传入 sessionId，
     * 同时建立 session → phone 索引，供 {@link #state} 接口在刷新后回查。
     *
     * @return {@code { token: "..." }}
     */
    @PostMapping("/sms/verify")
    public R<Map<String, String>> verify(@RequestBody @Valid VerifyCodeRequest req) {
        String token = visitorAuthService.verifyCode(req.getPhone(), req.getCode(), req.getSessionId());
        return R.ok(Map.of("token", token));
    }

    /**
     * 按 sessionId 回查当前会话的认证状态。
     *
     * <p>访客刷新页面后本地丢失 authToken/isAuth，通过此接口即可恢复：
     * <ul>
     *   <li>已认证：返回 {@code {authenticated:true, phoneMask:"138****5678"}}</li>
     *   <li>未认证：返回 {@code {authenticated:false}}</li>
     *   <li>sessionId 非法：HTTP 400</li>
     * </ul>
     *
     * <p>不返回完整手机号；不返回 token（token 仅在 verify 通道下发一次，
     * 前端刷新后无法通过 sessionId 反查 token，只能感知"已认证"状态）。
     */
    @GetMapping("/state")
    public R<Map<String, Object>> state(@RequestParam String sessionId) {
        if (sessionId == null || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return R.fail(400, "非法的 sessionId 格式");
        }
        Optional<String> phoneOpt = visitorAuthService.resolveSessionPhone(sessionId);
        Map<String, Object> body = new HashMap<>(2);
        body.put("authenticated", phoneOpt.isPresent());
        phoneOpt.ifPresent(p -> body.put("phoneMask", VisitorAuthService.maskPhone(p)));
        return R.ok(body);
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

        /**
         * 可选：会话 ID，非空时同时建立 session → phone 绑定。
         *
         * <p>刷新恢复场景推荐传入；纯 token 场景（未来座席端复用）可省略。
         */
        @Pattern(regexp = "^[a-zA-Z0-9_\\-]{1,64}$", message = "sessionId 格式非法")
        private String sessionId;
    }
}
