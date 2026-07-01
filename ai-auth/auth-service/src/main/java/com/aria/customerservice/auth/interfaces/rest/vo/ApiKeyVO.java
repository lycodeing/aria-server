package com.aria.customerservice.auth.interfaces.rest.vo;
import lombok.Data;

@Data
public class ApiKeyVO {
    private Long id;
    private String name;
    private String accessKey;
    private String secretKey;
    private String status;
    private String expiresAt;
}
