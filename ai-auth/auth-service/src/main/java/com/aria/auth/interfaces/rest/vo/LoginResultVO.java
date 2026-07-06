package com.aria.auth.interfaces.rest.vo;

import lombok.Data;

import java.util.List;

@Data
public class LoginResultVO {
    private String tokenName;
    private String tokenValue;
    private Long expiresIn;
    private Long userId;
    private String username;
    private String displayName;
    private List<String> roles;
    private Boolean mustChangePassword;
}
