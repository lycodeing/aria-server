package com.aidevplatform.customerservice.auth.infrastructure.persistence.role;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@TableName("cs_auth.sys_permission")
public class PermissionDO {
    private Long id;
    private String permissionKey;
    private String permissionName;
    private String module;
}
