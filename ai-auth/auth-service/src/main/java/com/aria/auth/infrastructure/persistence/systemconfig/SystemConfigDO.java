package com.aria.auth.infrastructure.persistence.systemconfig;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 系统配置持久化对象
 */
@Getter
@Setter
@TableName("cs_auth.system_config")
public class SystemConfigDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;

    private String configValue;

    /**
     * 配置类型：SYSTEM | CUSTOMER_SERVICE
     */
    private String configType;

    private String description;

    @TableField("is_enabled")
    private Boolean isEnabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
