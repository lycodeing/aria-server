package com.aria.auth.infrastructure.persistence.role;

import com.aria.auth.infrastructure.persistence.user.UserRepositoryImpl;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("cs_auth.sys_role")
public class RoleDO {
    /**
     * 主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    private String roleKey;
    private String roleName;
    private Boolean isSystem;
    private String status;

    /**
     * 非 DB 字段：批量查询时由 XML Mapper 通过 AS userId 映射，
     * 供 {@link UserRepositoryImpl#search}
     * 按用户分组角色，消除 N+1 查询。
     */
    @TableField(exist = false)
    private Long userId;
}
