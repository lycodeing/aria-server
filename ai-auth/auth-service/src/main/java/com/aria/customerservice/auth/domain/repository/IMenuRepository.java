package com.aria.auth.domain.repository;

import com.aria.auth.domain.model.menu.Menu;

import java.util.List;
import java.util.Optional;

/**
 * 菜单仓储接口（Domain 层定义，Infrastructure 层实现）。
 */
public interface IMenuRepository {

    Menu save(Menu menu);

    Optional<Menu> findById(Long id);

    /** 查询全量菜单列表（按 sortOrder 升序） */
    List<Menu> findAll();

    /** 查询用户拥有的可见菜单（通过角色-菜单关联） */
    List<Menu> findByUserId(Long userId);

    /** 查询角色已分配的菜单 ID 列表 */
    List<Long> findMenuIdsByRoleId(Long roleId);

    /** 统计指定父节点下的子菜单数量 */
    long countChildren(Long parentId);

    void delete(Long id);

    /** 全量替换角色菜单分配（先删后批量插入） */
    void assignMenusToRole(Long roleId, List<Long> menuIds);
}
