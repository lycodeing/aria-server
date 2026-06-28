package com.aidevplatform.customerservice.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.aidevplatform.customerservice.auth.application.query.RolePageQuery;
import com.aidevplatform.customerservice.auth.application.service.MenuApplicationService;
import com.aidevplatform.customerservice.auth.application.service.RoleApplicationService;
import com.aidevplatform.customerservice.auth.domain.model.role.Permission;
import com.aidevplatform.customerservice.auth.domain.model.role.Role;
import com.aidevplatform.customerservice.auth.interfaces.assembler.RoleAssembler;
import com.aidevplatform.customerservice.auth.interfaces.rest.vo.*;
import com.aidevplatform.common.core.page.PageResult;
import com.aidevplatform.common.web.response.R;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色管理接口。
 * Controller 只负责参数接收/校验和响应组装，所有业务逻辑委托给 RoleApplicationService。
 * 不依赖任何基础设施对象（Mapper/DO）。
 */
@RestController
@RequestMapping("/api/v1/roles")
@SaCheckLogin
@RequiredArgsConstructor
@Validated
public class RoleController {

    private final RoleApplicationService roleAppService;
    private final MenuApplicationService menuService;

    @GetMapping
    public R<PageVO<RoleVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        RolePageQuery query = new RolePageQuery();
        query.setKeyword(keyword);
        query.setPage(page);
        query.setSize(size);
        PageResult<Role> result = roleAppService.list(query);
        List<RoleVO> vos = result.items().stream().map(RoleAssembler::toVO).toList();
        return R.ok(new PageVO<>(vos, result.total()));
    }

    @GetMapping("/{id}")
    public R<RoleVO> getById(@PathVariable Long id) {
        return R.ok(RoleAssembler.toVO(roleAppService.getById(id)));
    }

    @PostMapping
    public R<RoleVO> create(@RequestBody @Valid CreateRoleRequest req) {
        Role role = roleAppService.create(req.getRoleKey(), req.getRoleName(), req.getIsSystem());
        return R.ok(RoleAssembler.toVO(role));
    }

    @PutMapping("/{id}")
    public R<RoleVO> update(@PathVariable Long id, @RequestBody @Valid UpdateRoleRequest req) {
        Role role = roleAppService.update(id, req.getRoleName(), req.getStatus());
        return R.ok(RoleAssembler.toVO(role));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        roleAppService.delete(id);
        return R.ok();
    }

    /** 查询接口权限树（按 module 分组） */
    @GetMapping("/permissions/tree")
    public R<List<PermissionTreeVO>> permissionTree() {
        List<Permission> all = roleAppService.listAllPermissions();
        Map<String, List<Permission>> grouped = all.stream()
                .filter(p -> p.getModule() != null)
                .collect(Collectors.groupingBy(Permission::getModule,
                        LinkedHashMap::new, Collectors.toList()));
        List<PermissionTreeVO> tree = grouped.entrySet().stream()
                .map(e -> new PermissionTreeVO(
                        e.getKey(),
                        e.getValue().stream()
                                .map(p -> new PermissionItemVO(
                                        p.getPermissionKey(), p.getPermissionName()))
                                .toList()))
                .toList();
        return R.ok(tree);
    }

    /** 给角色分配接口权限（全量替换） */
    @PutMapping("/{id}/permissions")
    public R<AssignPermissionsVO> assignPermissions(
            @PathVariable Long id,
            @RequestBody @Valid AssignPermissionsRequest req) {
        roleAppService.assignPermissions(id, req.getPermissionIds());
        return R.ok(new AssignPermissionsVO(id, req.getPermissionIds(), "权限分配成功"));
    }

    /** 查询角色已分配的菜单 ID 列表 */
    @GetMapping("/{id}/menus")
    public R<List<Long>> getRoleMenus(@PathVariable Long id) {
        return R.ok(menuService.getRoleMenuIds(id));
    }

    /** 给角色分配菜单（批量替换） */
    @PutMapping("/{id}/menus")
    public R<Void> assignMenus(@PathVariable Long id,
                                @RequestBody @Valid AssignMenusRequest req) {
        menuService.assignMenusToRole(id, req.getMenuIds());
        return R.ok();
    }

    /** 查询角色数据权限范围 */
    @GetMapping("/{id}/data-scope")
    public R<Map<String, Object>> getDataScope(@PathVariable Long id) {
        String scopeType = roleAppService.getDataScope(id);
        return R.ok(Map.of("roleId", id, "scopeType", scopeType));
    }

    /** 设置角色数据权限范围 */
    @PutMapping("/{id}/data-scope")
    public R<Void> setDataScope(@PathVariable Long id,
                                 @RequestBody @Valid DataScopeRequest req) {
        roleAppService.setDataScope(id, req.getScopeType());
        return R.ok();
    }

    // -------------------------------------------------------
    // Request DTO（Lombok @Data + @Valid 校验）
    // -------------------------------------------------------

    @Data
    public static class CreateRoleRequest {
        @NotBlank(message = "角色标识不能为空")
        private String roleKey;
        @NotBlank(message = "角色名称不能为空")
        private String roleName;
        private Boolean isSystem;
    }

    @Data
    public static class UpdateRoleRequest {
        private String roleName;
        private String status;
    }

    @Data
    public static class AssignPermissionsRequest {
        @NotNull(message = "权限 ID 列表不能为 null")
        private List<Long> permissionIds;
    }

    @Data
    public static class AssignMenusRequest {
        @NotNull(message = "菜单 ID 列表不能为 null")
        private List<Long> menuIds;
    }

    @Data
    public static class DataScopeRequest {
        @NotBlank(message = "数据权限范围类型不能为空")
        private String scopeType;
    }
}
