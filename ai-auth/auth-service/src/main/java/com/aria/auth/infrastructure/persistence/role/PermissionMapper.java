package com.aria.auth.infrastructure.persistence.role;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 权限 Mapper。
 * 跨表 JOIN 查询定义在 PermissionMapper.xml 中。
 */
@Mapper
public interface PermissionMapper extends BaseMapper<PermissionDO> {

    /**
     * 查询指定用户拥有的所有权限（通过角色关联）。
     * SQL：sys_permission JOIN sys_role_permission JOIN sys_user_role
     */
    List<PermissionDO> findPermissionsByUserId(@Param("userId") Long userId);
}
