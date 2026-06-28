package com.aidevplatform.customerservice.auth.application.command;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/**
 * 登录命令。
 * 包含所有登录所需信息，不携带 HTTP 关注点（HttpServletRequest 由 Controller 提前解析）。
 */
@Getter
public class LoginCommand {

    @NotBlank(message = "用户名不能为空")
    private final String username;

    @NotBlank(message = "密码不能为空")
    private final String password;

    private final boolean rememberMe;

    /** 客户端 IP，由 Controller 层从 HttpServletRequest 提取后传入，不允许为 null */
    private final String clientIp;

    public LoginCommand(String username, String password,
                        boolean rememberMe, String clientIp) {
        this.username = username;
        this.password = password;
        this.rememberMe = rememberMe;
        this.clientIp = clientIp;
    }
}
