package com.aidevplatform.customerservice.auth.infrastructure.persistence.role;

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
