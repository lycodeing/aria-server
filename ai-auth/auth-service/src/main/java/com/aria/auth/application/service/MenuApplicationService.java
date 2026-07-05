package com.aria.auth.application.service;

import com.aria.auth.application.command.CreateMenuCommand;
import com.aria.auth.application.command.UpdateMenuCommand;
import com.aria.auth.application.result.MenuAdminVO;
import com.aria.auth.application.result.RouteMetaVO;
import com.aria.auth.application.result.RouteVO;
import com.aria.auth.domain.model.menu.Menu;
import com.aria.auth.domain.repository.IMenuRepository;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.exception.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 菜单应用服务：菜单树查询、动态路由生成、菜单 CRUD。
 *
 * <p>只依赖 Domain 层仓储接口，不直接接触任何 Mapper/DO。
 * 所有公开接口均返回强类型 VO，不使用 Map&lt;String, Object&gt;。
 *
 * @author aria
 */
@Slf4j
@Service
public class MenuApplicationService {

    /**
     * 菜单类型：按钮/权限标识
     */
    private static final String MENU_TYPE_BUTTON = "BUTTON";

    /**
     * 根节点父 ID
     */
    private static final long ROOT_PARENT_ID = 0L;

    /**
     * 路由排序默认值（未配置时兜底）
     */
    private static final int DEFAULT_SORT_ORDER = 999;
    /**
     * 最大递归深度，防止环形菜单数据导致 StackOverflowError
     */
    private static final int MAX_MENU_DEPTH = 10;
    private final IMenuRepository menuRepo;

    // -------------------------------------------------------
    // 前端动态路由
    // -------------------------------------------------------

    public MenuApplicationService(IMenuRepository menuRepo) {
        this.menuRepo = menuRepo;
    }

    /**
     * 一次 DB 查询同时构建路由树和权限码，供登录后菜单初始化使用。
     * 原 buildUserRoutes + getUserPermissionCodes 需要两次 DB 查询，此方法合并为一次。
     *
     * @param userId 当前登录用户 ID
     * @return 路由树 + 权限码的组合结果
     */
    public UserMenuResult buildUserMenus(Long userId) {
        List<Menu> all = menuRepo.findByUserId(userId);  // 只查一次
        Map<Long, List<Menu>> groupByParent = groupByParentId(all);

        List<RouteVO> routes = buildRouteNodes(groupByParent, ROOT_PARENT_ID, 0);
        List<String> codes = all.stream()
                .filter(menu -> MENU_TYPE_BUTTON.equals(menu.getMenuType()))
                .filter(menu -> menu.getPermissionKey() != null
                        && !menu.getPermissionKey().isBlank())
                .map(Menu::getPermissionKey)
                .distinct()
                .collect(Collectors.toList());

        return new UserMenuResult(routes, codes);
    }

    /**
     * 构建当前用户的可见路由树（Vben 格式）。
     * 仅返回 DIRECTORY 和 MENU 类型（BUTTON 不生成路由）。
     *
     * @param userId 当前登录用户 ID
     * @return Vben 格式路由树
     */
    public List<RouteVO> buildUserRoutes(Long userId) {
        List<Menu> menus = menuRepo.findByUserId(userId).stream()
                .filter(menu -> !MENU_TYPE_BUTTON.equals(menu.getMenuType()))
                .collect(Collectors.toList());
        Map<Long, List<Menu>> groupByParent = groupByParentId(menus);
        return buildRouteNodes(groupByParent, ROOT_PARENT_ID, 0);
    }

    // -------------------------------------------------------
    // 系统管理：全量菜单树
    // -------------------------------------------------------

    /**
     * 获取当前用户按钮级权限码列表（用于前端按钮显隐）。
     *
     * @param userId 当前登录用户 ID
     * @return 权限码列表
     */
    public List<String> getUserPermissionCodes(Long userId) {
        return menuRepo.findByUserId(userId).stream()
                .filter(menu -> MENU_TYPE_BUTTON.equals(menu.getMenuType()))
                .filter(menu -> menu.getPermissionKey() != null
                        && !menu.getPermissionKey().isBlank())
                .map(Menu::getPermissionKey)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 返回全量菜单树（树形结构，含 BUTTON）。
     *
     * @return 菜单树
     */
    public List<MenuAdminVO> getAllMenuTree() {
        List<Menu> all = menuRepo.findAll();
        Map<Long, List<Menu>> groupByParent = groupByParentId(all);
        return buildAdminNodes(groupByParent, ROOT_PARENT_ID);
    }

    /**
     * 新增菜单/按钮。
     *
     * @param cmd 新建菜单命令
     * @return 创建后的菜单 VO
     */
    @Transactional(rollbackFor = Exception.class)
    public MenuAdminVO create(CreateMenuCommand cmd) {
        Menu menu = Menu.create(
                cmd.getParentId(), cmd.getMenuType(), cmd.getMenuName(),
                cmd.getMenuKey(), cmd.getPath(), cmd.getComponent(),
                cmd.getIcon(), cmd.getSortOrder(), cmd.getIsVisible(),
                cmd.getIsCache(), cmd.getIsExternal(), cmd.getRedirect(),
                cmd.getPermissionKey(), cmd.getStatus(), cmd.getRemark());
        menuRepo.save(menu);
        return toAdminVO(menu, Collections.emptyList());
    }

    /**
     * 更新菜单。
     *
     * @param id  菜单 ID
     * @param cmd 更新菜单命令
     * @return 更新后的菜单 VO
     */
    @Transactional(rollbackFor = Exception.class)
    public MenuAdminVO update(Long id, UpdateMenuCommand cmd) {
        Menu menu = menuRepo.findById(id)
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.NOT_FOUND, "菜单"));
        menu.update(
                cmd.getParentId(), cmd.getMenuType(), cmd.getMenuName(),
                cmd.getMenuKey(), cmd.getPath(), cmd.getComponent(),
                cmd.getIcon(), cmd.getSortOrder(), cmd.getIsVisible(),
                cmd.getIsCache(), cmd.getIsExternal(), cmd.getRedirect(),
                cmd.getPermissionKey(), cmd.getStatus(), cmd.getRemark());
        menuRepo.save(menu);
        return toAdminVO(menu, Collections.emptyList());
    }

    /**
     * 删除菜单（存在子菜单时禁止删除）。
     *
     * @param id 菜单 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (menuRepo.countChildren(id) > 0) {
            throw BusinessException.of("MENU_HAS_CHILDREN", "请先删除子菜单");
        }
        menuRepo.delete(id);
    }

    /**
     * 查询角色已分配的菜单 ID 集合。
     *
     * @param roleId 角色 ID
     * @return 菜单 ID 列表
     */
    public List<Long> getRoleMenuIds(Long roleId) {
        return menuRepo.findMenuIdsByRoleId(roleId);
    }

    // -------------------------------------------------------
    // 内部工具：O(n) 构建路由树
    // -------------------------------------------------------

    /**
     * 给角色重新分配菜单（先删后批量插入）。
     *
     * @param roleId  角色 ID
     * @param menuIds 新菜单 ID 列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignMenusToRole(Long roleId, List<Long> menuIds) {
        menuRepo.assignMenusToRole(roleId, menuIds);
    }

    /**
     * 预先按 parentId 分组，避免递归内层 O(n) 遍历，整体复杂度从 O(n²) 降至 O(n)。
     */
    private Map<Long, List<Menu>> groupByParentId(List<Menu> menus) {
        return menus.stream().collect(Collectors.groupingBy(
                menu -> menu.getParentId() != null ? menu.getParentId() : ROOT_PARENT_ID));
    }

    private List<RouteVO> buildRouteNodes(Map<Long, List<Menu>> groupByParent,
                                          Long parentId, int depth) {
        if (depth > MAX_MENU_DEPTH) {
            log.warn("[Menu] 路由树深度超过 {} 层，停止递归 parentId={}", MAX_MENU_DEPTH, parentId);
            return List.of();
        }
        List<Menu> children = groupByParent.getOrDefault(parentId, Collections.emptyList());
        List<RouteVO> result = new ArrayList<>(children.size());
        for (Menu menu : children) {
            RouteMetaVO meta = new RouteMetaVO(
                    menu.getMenuName(),
                    menu.getIcon(),
                    menu.getIsCache(),
                    Boolean.FALSE.equals(menu.getIsVisible()) ? Boolean.TRUE : null,
                    Boolean.TRUE.equals(menu.getIsExternal()) ? menu.getPath() : null,
                    menu.getSortOrder());
            List<RouteVO> subChildren = buildRouteNodes(groupByParent, menu.getId(), depth + 1);
            result.add(new RouteVO(
                    menu.getMenuKey(),
                    menu.getPath(),
                    (menu.getComponent() != null && !menu.getComponent().isBlank())
                            ? menu.getComponent() : null,
                    menu.getRedirect(),
                    meta,
                    subChildren.isEmpty() ? null : subChildren));
        }
        result.sort(Comparator.comparingInt(route -> {
            RouteMetaVO meta = route.getMeta();
            return meta != null && meta.getOrder() != null
                    ? meta.getOrder() : DEFAULT_SORT_ORDER;
        }));
        return result;
    }

    private List<MenuAdminVO> buildAdminNodes(Map<Long, List<Menu>> groupByParent,
                                              Long parentId) {
        return buildAdminNodes(groupByParent, parentId, 0);
    }

    private List<MenuAdminVO> buildAdminNodes(Map<Long, List<Menu>> groupByParent,
                                              Long parentId, int depth) {
        if (depth > MAX_MENU_DEPTH) {
            log.warn("[Menu] 管理树深度超过 {} 层，停止递归 parentId={}", MAX_MENU_DEPTH, parentId);
            return List.of();
        }
        List<Menu> children = groupByParent.getOrDefault(parentId, Collections.emptyList());
        List<MenuAdminVO> result = new ArrayList<>(children.size());
        for (Menu menu : children) {
            List<MenuAdminVO> subChildren = buildAdminNodes(groupByParent, menu.getId(), depth + 1);
            result.add(toAdminVO(menu, subChildren));
        }
        return result;
    }

    private MenuAdminVO toAdminVO(Menu menu, List<MenuAdminVO> children) {
        return new MenuAdminVO(
                menu.getId(), menu.getParentId(), menu.getMenuType(),
                menu.getMenuName(), menu.getMenuKey(), menu.getPath(),
                menu.getComponent(), menu.getIcon(), menu.getSortOrder(),
                menu.getIsVisible(), menu.getIsCache(), menu.getIsExternal(),
                menu.getRedirect(), menu.getPermissionKey(),
                menu.getStatus(), menu.getRemark(),
                children.isEmpty() ? null : children);
    }

    // -------------------------------------------------------
    // 结果 VO
    // -------------------------------------------------------

    /**
     * 用户菜单初始化结果（路由树 + 权限码），一次 DB 查询返回。
     */
    public record UserMenuResult(List<RouteVO> routes, List<String> codes) {
    }
}
