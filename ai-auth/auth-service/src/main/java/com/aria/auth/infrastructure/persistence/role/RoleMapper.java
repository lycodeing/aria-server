package com.aria.auth.infrastructure.persistence.role;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色 Mapper 接口。
 * 所有自定义 SQL 统一维护在 mapper/RoleMapper.xml，不使用 @Select/@Insert/@Delete 注解。
 */
@Mapper
public interface RoleMapper extends BaseMapper<RoleDO> {

    /**
     * 查询用户拥有的角色列表（状态为 active）。
     */
    List<RoleDO> findRolesByUserId(@Param("userId") Long userId);

    /**
     * 批量查询多个用户的角色列表，消除 toDomain 中的 N+1 问题。
     * 返回 List 由调用方按 userId 分组。
     *
     * @param userIds 用户 ID 列表，不得为空
     * @return 所有涉及用户的角色列表（含 userId 字段用于分组）
     */
    List<RoleDO> findRolesByUserIds(@Param("userIds") List<Long> userIds);

    /**
     * 删除角色的所有接口权限关联（先清后插）。
     */
    int deleteRolePermissions(@Param("roleId") Long roleId);

    /**
     * 插入角色-接口权限关联记录。
     */
    int insertRolePermission(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);

    /**
     * 查询角色的数据权限范围类型。
     * DataScopeAspect 调用，返回 ALL/DEPT_TREE/DEPT_ONLY/CUSTOM_DEPT/SELF。
     */
    String findScopeTypeByRoleId(@Param("roleId") Long roleId);

    /**
     * 插入或更新角色数据权限范围（UPSERT）。
     */
    int upsertDataScope(@Param("roleId") Long roleId, @Param("scopeType") String scopeType);
}
