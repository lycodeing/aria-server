package com.aria.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.auth.application.command.CreateMenuCommand;
import com.aria.auth.application.command.UpdateMenuCommand;
import com.aria.auth.application.result.MenuAdminVO;
import com.aria.auth.application.result.RouteVO;
import com.aria.auth.application.service.MenuApplicationService;
import com.aria.common.web.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 菜单管理接口。
 * <ul>
 *   <li>GET  /api/v1/menus/me → 当前用户可见路由树（Vben 动态路由格式）</li>
 *   <li>GET  /api/v1/menus    → 全量菜单树（系统管理页）</li>
 * </ul>
 *
 * @author aria
 */
@RestController
@RequestMapping("/api/v1/menus")
@SaCheckLogin
@RequiredArgsConstructor
public class MenuController {

    private final MenuApplicationService menuService;

    /**
     * 当前用户动态路由（Vben 前端 getAllMenusApi 调用此接口）。
     * 返回 RouteVO[] 格式，BUTTON 类型不生成路由。
     *
     * @return 路由树
     */
    @GetMapping("/me")
    public R<List<RouteVO>> myRoutes() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(menuService.buildUserRoutes(userId));
    }

    /**
     * 全量菜单树，含 BUTTON，供系统管理页菜单配置使用。
     *
     * @return 菜单树
     */
    @GetMapping
    public R<List<MenuAdminVO>> allMenus() {
        return R.ok(menuService.getAllMenuTree());
    }

    /**
     * 新增菜单或按钮。
     *
     * @param cmd 新建菜单命令
     * @return 创建后的菜单 VO
     */
    @PostMapping
    public R<MenuAdminVO> create(@RequestBody CreateMenuCommand cmd) {
        return R.ok(menuService.create(cmd));
    }

    /**
     * 编辑菜单。
     *
     * @param id  菜单 ID
     * @param cmd 更新菜单命令
     * @return 更新后的菜单 VO
     */
    @PutMapping("/{id}")
    public R<MenuAdminVO> update(@PathVariable Long id,
                                  @RequestBody UpdateMenuCommand cmd) {
        return R.ok(menuService.update(id, cmd));
    }

    /**
     * 删除菜单（有子菜单时拒绝删除）。
     *
     * @param id 菜单 ID
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return R.ok();
    }
}
