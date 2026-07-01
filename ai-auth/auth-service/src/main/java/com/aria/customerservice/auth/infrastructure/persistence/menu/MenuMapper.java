package com.aria.customerservice.auth.infrastructure.persistence.menu;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 菜单 Mapper 接口。
 * 所有自定义 SQL 统一维护在 mapper/MenuMapper.xml，不使用 @Select/@Insert/@Delete 注解。
 */
@Mapper
public interface MenuMapper extends BaseMapper<MenuDO> {

    /**
     * 查询用户拥有的菜单列表（通过角色-菜单关联）。
     * 仅返回 status=active 且 is_visible=true 的菜单，用于构建前端动态路由。
     */
    List<MenuDO> findMenusByUserId(@Param("userId") Long userId);

    /**
     * 查询角色已关联的菜单 ID 列表（供角色管理页回显）。
     */
    List<Long> findMenuIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 批量删除角色的菜单关联（分配菜单前先清空，保证幂等）。
     */
    int deleteRoleMenus(@Param("roleId") Long roleId);

    /**
     * 批量插入角色-菜单关联记录（单次 SQL，避免 N 次独立 INSERT）。
     *
     * @param roleId  角色 ID
     * @param menuIds 菜单 ID 列表（非空）
     */
    int batchInsertRoleMenus(@Param("roleId") Long roleId, @Param("menuIds") List<Long> menuIds);
}

