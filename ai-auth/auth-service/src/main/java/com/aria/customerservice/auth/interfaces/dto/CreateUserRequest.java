package com.aria.customerservice.auth.interfaces.dto;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateUserRequest {
    @NotBlank(message = "用户名不能为空") @Size(min = 3, max = 50, message = "用户名3-50位")
    private String username;
    @NotBlank(message = "显示名不能为空")
    private String displayName;
    @Email(message = "邮箱格式不正确")
    private String email;
    private String phone;
    @NotBlank(message = "密码不能为空") @Size(min = 8, message = "密码至少8位")
    private String password;
}
