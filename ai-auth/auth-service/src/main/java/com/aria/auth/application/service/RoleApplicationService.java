package com.aria.auth.application.service;

import com.aria.auth.application.query.RolePageQuery;
import com.aria.auth.domain.model.role.Permission;
import com.aria.auth.domain.model.role.Role;
import com.aria.auth.domain.repository.IPermissionRepository;
import com.aria.auth.domain.repository.IRoleRepository;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.exception.CommonErrorCode;
import com.aria.common.core.page.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色应用服务。
 *
 * <p>只依赖 Domain 层仓储接口，不接触任何基础设施对象（Mapper/DO）。
 * 负责角色 CRUD、接口权限分配、菜单分配、数据权限配置等业务逻辑编排。
 *
 * @author aria
 */
@Service
public class RoleApplicationService {

    /**
     * 角色启用状态值
     */
    private static final String STATUS_ACTIVE = "active";

    /**
     * 角色停用状态值
     */
    private static final String STATUS_INACTIVE = "inactive";

    private final IRoleRepository roleRepo;
    private final IPermissionRepository permissionRepo;

    public RoleApplicationService(IRoleRepository roleRepo,
                                  IPermissionRepository permissionRepo) {
        this.roleRepo = roleRepo;
        this.permissionRepo = permissionRepo;
    }

    // -------------------------------------------------------
    // 角色查询
    // -------------------------------------------------------

    /**
     * 分页查询角色列表。
     *
     * @param query 分页查询条件（含关键词和分页参数）
     * @return 分页结果
     */
    public PageResult<Role> list(RolePageQuery query) {
        return roleRepo.search(query);
    }

    /**
     * 按 ID 查询单个角色，不存在时抛业务异常。
     *
     * @param id 角色 ID
     * @return Role 领域对象
     */
    public Role getById(Long id) {
        return roleRepo.findById(id)
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.NOT_FOUND, "角色"));
    }

    // -------------------------------------------------------
    // 角色写操作
    // -------------------------------------------------------

    /**
     * 新建角色。
     *
     * @param roleKey  角色标识（全局唯一）
     * @param roleName 角色名称
     * @param isSystem 是否系统内置角色
     * @return 创建后的 Role 领域对象
     */
    @Transactional(rollbackFor = Exception.class)
    public Role create(String roleKey, String roleName, Boolean isSystem) {
        if (roleRepo.existsByRoleKey(roleKey)) {
            throw BusinessException.of("ROLE_KEY_EXISTS", "角色标识已存在");
        }
        Role role = Role.create(roleKey, roleName, Boolean.TRUE.equals(isSystem));
        return roleRepo.save(role);
    }

    /**
     * 更新角色名称或状态。
     *
     * @param id       角色 ID
     * @param roleName 新角色名称（null 表示不修改）
     * @param status   新状态（"active" 或 "inactive"，null 表示不修改）
     * @return 更新后的 Role 领域对象
     */
    @Transactional(rollbackFor = Exception.class)
    public Role update(Long id, String roleName, String status) {
        Role role = getById(id);
        if (roleName != null && !roleName.isBlank()) {
            role.rename(roleName);
        }
        if (STATUS_INACTIVE.equals(status)) {
            role.deactivate();
        } else if (STATUS_ACTIVE.equals(status)) {
            role.activate();
        }
        return roleRepo.save(role);
    }

    /**
     * 删除角色（系统内置角色不允许删除）。
     *
     * @param id 角色 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Role role = getById(id);
        if (role.isSystem()) {
            throw BusinessException.of("ROLE_IS_SYSTEM", "系统内置角色不允许删除");
        }
        roleRepo.delete(id);
    }

    // -------------------------------------------------------
    // 接口权限分配
    // -------------------------------------------------------

    /**
     * 查询所有接口权限，按 module 分组，返回 {@code Map<module, List<Permission>>}。
     * 分组逻辑属于"如何组织权限数据"的业务规则，由 Service 层负责，Controller 只做 VO 转换。
     *
     * @return 按 module 分组的有序 Map（LinkedHashMap 保留插入顺序）
     */
    public java.util.Map<String, List<Permission>> getPermissionTree() {
        return permissionRepo.findAll().stream()
                .filter(p -> p.getModule() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        Permission::getModule,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
    }

    /**
     * 查询所有接口权限（用于树形展示，分组逻辑由 Controller 负责）。
     *
     * @return 权限列表
     */
    public List<Permission> listAllPermissions() {
        return permissionRepo.findAll();
    }

    /**
     * 给角色分配接口权限（全量替换：先删后插）。
     *
     * @param roleId        角色 ID
     * @param permissionIds 权限 ID 列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        getById(roleId);
        roleRepo.assignPermissions(roleId, permissionIds);
    }

    // -------------------------------------------------------
    // 数据权限范围配置
    // -------------------------------------------------------

    /**
     * 查询角色数据权限范围，未配置时默认返回 SELF。
     *
     * @param roleId 角色 ID
     * @return 数据权限范围类型
     */
    public String getDataScope(Long roleId) {
        return roleRepo.findDataScope(roleId);
    }

    /**
     * 设置角色数据权限范围（UPSERT）。
     *
     * @param roleId    角色 ID
     * @param scopeType ALL/DEPT_TREE/DEPT_ONLY/CUSTOM_DEPT/SELF
     */
    @Transactional(rollbackFor = Exception.class)
    public void setDataScope(Long roleId, String scopeType) {
        getById(roleId);
        roleRepo.upsertDataScope(roleId, scopeType);
    }
}
