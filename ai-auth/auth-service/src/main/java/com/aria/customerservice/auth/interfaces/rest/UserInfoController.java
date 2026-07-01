package com.aria.customerservice.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.customerservice.auth.application.result.UserInfoResult;
import com.aria.customerservice.auth.application.service.UserInfoApplicationService;
import com.aria.common.web.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户信息接口，对应 Vben 前端 getUserInfoApi() → GET /user/info。
 * 返回 Vben UserInfo 格式：userId/username/realName/avatar/roles/homePath。
 *
 * @author aria
 */
@RestController
@RequestMapping("/api/v1/user")
@SaCheckLogin
@RequiredArgsConstructor
public class UserInfoController {

    private final UserInfoApplicationService userInfoService;

    /**
     * 获取当前登录用户信息（Vben UserInfo 格式）。
     * Vben 在登录后调用此接口存储用户信息，roles 用于动态路由权限控制。
     *
     * @return 用户信息结果
     */
    @GetMapping("/info")
    public R<UserInfoResult> info() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(userInfoService.getUserInfo(userId));
    }
}
