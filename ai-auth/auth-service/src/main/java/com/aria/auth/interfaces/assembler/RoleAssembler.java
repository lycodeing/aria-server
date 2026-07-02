package com.aria.auth.interfaces.assembler;

import com.aria.auth.domain.model.role.Role;
import com.aria.auth.interfaces.rest.vo.RoleVO;

/**
 * 角色领域对象 ↔ 接口层 VO 转换器。
 * 静态工具类，集中管理 Role → RoleVO 的映射逻辑，Controller 不内联转换代码。
 */
public final class RoleAssembler {

    private RoleAssembler() {}

    public static RoleVO toVO(Role role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setRoleKey(role.getRoleKey());
        vo.setRoleName(role.getRoleName());
        vo.setIsSystem(role.isSystem());
        vo.setStatus(role.getStatus());
        return vo;
    }
}
