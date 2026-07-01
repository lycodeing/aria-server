package com.aria.customerservice.auth.infrastructure.persistence.menu;

import com.aria.customerservice.auth.domain.model.menu.Menu;
import com.aria.customerservice.auth.domain.repository.IMenuRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 菜单仓储实现。
 * 使用 Menu.reconstitute() 重建领域对象，无反射操作。
 */
@Repository
public class MenuRepositoryImpl implements IMenuRepository {

    private final MenuMapper menuMapper;

    public MenuRepositoryImpl(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    @Override
    public Menu save(Menu menu) {
        MenuDO entity = toEntity(menu);
        if (menu.getId() == null) {
            menuMapper.insert(entity);
            menu.assignId(entity.getId());
        } else {
            menuMapper.updateById(entity);
        }
        return menu;
    }

    @Override
    public Optional<Menu> findById(Long id) {
        return Optional.ofNullable(menuMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public List<Menu> findAll() {
        return menuMapper.selectList(
                new LambdaQueryWrapper<MenuDO>().orderByAsc(MenuDO::getSortOrder))
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Menu> findByUserId(Long userId) {
        return menuMapper.findMenusByUserId(userId)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Long> findMenuIdsByRoleId(Long roleId) {
        return menuMapper.findMenuIdsByRoleId(roleId);
    }

    @Override
    public long countChildren(Long parentId) {
        return menuMapper.selectCount(
                new LambdaQueryWrapper<MenuDO>().eq(MenuDO::getParentId, parentId));
    }

    @Override
    public void delete(Long id) {
        menuMapper.deleteById(id);
    }

    @Override
    public void assignMenusToRole(Long roleId, List<Long> menuIds) {
        menuMapper.deleteRoleMenus(roleId);
        if (menuIds != null && !menuIds.isEmpty()) {
            menuMapper.batchInsertRoleMenus(roleId, menuIds);
        }
    }

    // -------------------------------------------------------
    // 内部转换（无反射）
    // -------------------------------------------------------

    private Menu toDomain(MenuDO e) {
        return Menu.reconstitute(
                e.getId(), e.getParentId(), e.getMenuType(),
                e.getMenuName(), e.getMenuKey(), e.getPath(),
                e.getComponent(), e.getIcon(), e.getSortOrder(),
                e.getIsVisible(), e.getIsCache(), e.getIsExternal(),
                e.getRedirect(), e.getPermissionKey(),
                e.getStatus(), e.getRemark(), e.getCreatedBy());
    }

    private MenuDO toEntity(Menu m) {
        MenuDO e = new MenuDO();
        if (m.getId() != null) e.setId(m.getId());
        e.setParentId(m.getParentId());
        e.setMenuType(m.getMenuType());
        e.setMenuName(m.getMenuName());
        e.setMenuKey(m.getMenuKey());
        e.setPath(m.getPath());
        e.setComponent(m.getComponent());
        e.setIcon(m.getIcon());
        e.setSortOrder(m.getSortOrder());
        e.setIsVisible(m.getIsVisible());
        e.setIsCache(m.getIsCache());
        e.setIsExternal(m.getIsExternal());
        e.setRedirect(m.getRedirect());
        e.setPermissionKey(m.getPermissionKey());
        e.setStatus(m.getStatus());
        e.setRemark(m.getRemark());
        e.setCreatedBy(m.getCreatedBy());
        return e;
    }
}
