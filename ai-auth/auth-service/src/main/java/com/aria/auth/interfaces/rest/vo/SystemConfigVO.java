package com.aria.auth.interfaces.rest.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置视图对象
 */
@Data
@Builder
public class SystemConfigVO {

    private Long id;

    private String configKey;

    private String configValue;

    private String configType;

    private String description;

    @JsonProperty("isEnabled")
    private Boolean isEnabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
