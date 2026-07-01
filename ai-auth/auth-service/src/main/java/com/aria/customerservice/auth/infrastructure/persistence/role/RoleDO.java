package com.aria.customerservice.auth.infrastructure.persistence.role;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@TableName("cs_auth.sys_role")
public class RoleDO {
    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;

    private String roleKey;
    private String roleName;
    private Boolean isSystem;
    private String status;
}
