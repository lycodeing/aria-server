package com.aidevplatform.customerservice.auth.interfaces.rest.vo;
import lombok.Data;

@Data
public class AuditLogVO {
    private Long id;
    private Long userId;
    private String username;
    private String actionType;
    private String ipAddress;
    private String result;
    private String createdAt;
}
