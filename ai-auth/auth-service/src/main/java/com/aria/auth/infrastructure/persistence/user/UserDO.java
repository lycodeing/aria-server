package com.aria.auth.infrastructure.persistence.user;

import com.aria.auth.infrastructure.persistence.typehandler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@TableName(value = "cs_auth.sys_user", autoResultMap = true)
public class UserDO {
    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;


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

    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
}
