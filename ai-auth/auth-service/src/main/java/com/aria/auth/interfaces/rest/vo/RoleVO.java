package com.aria.auth.interfaces.rest.vo;
import lombok.Data;

@Data
public class RoleVO {
    private Long id;
    private String roleKey;
    private String roleName;
    private Boolean isSystem;
    private String status;
}
