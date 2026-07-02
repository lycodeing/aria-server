package com.aria.auth.interfaces.rest.vo;
import lombok.Data;

@Data
public class UserVO {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private String phone;
    private String status;
    private String provider;
    private String lastLoginAt;
}
