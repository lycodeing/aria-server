package com.aria.auth.infrastructure.persistence.user;

import com.aria.auth.infrastructure.persistence.typehandler.JsonbTypeHandler;
import com.aria.common.core.mybatis.BaseDO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName(value = "cs_auth.sys_user", autoResultMap = true)
public class UserDO extends BaseDO {
    /**
     * 主键 ID
     */
    @TableId(type = IdType.INPUT)
    private Long id;


    private String username;
    private String displayName;
    private String email;
    private String phone;
    private String passwordHash;
    private String status;
    private String provider;
    private Integer loginFailCount;
    private LocalDateTime lockedUntil;
    private Boolean mustChangePassword;
    private LocalDateTime passwordChangedAt;

    @TableField(value = "password_history", typeHandler = JsonbTypeHandler.class)
    private String passwordHistoryJson;

    private LocalDateTime deletedAt;

    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
}
