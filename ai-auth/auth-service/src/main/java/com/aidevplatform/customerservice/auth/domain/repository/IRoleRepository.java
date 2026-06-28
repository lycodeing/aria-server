package com.aidevplatform.customerservice.auth.domain.repository;

import com.aidevplatform.customerservice.auth.application.query.RolePageQuery;
import com.aidevplatform.customerservice.auth.domain.model.role.Role;
import com.aidevplatform.common.core.page.PageResult;

import java.util.List;
import java.util.Optional;

/**
 * 角色仓储接口（Domain 层定义，Infrastructure 层实现）。
 */
public interface IRoleRepository {

    Role save(Role role);
    Optional<Role> findById(Long id);
    boolean existsByRoleKey(String roleKey);

    /**
     * 分页搜索角色列表。
     *
     * @param query 分页查询条件
     * @return 分页结果
     */
    PageResult<Role> search(RolePageQuery query);

    void delete(Long id);
    List<Role> findByUserId(Long userId);
    void assignPermissions(Long roleId, List<Long> permissionIds);
    String findDataScope(Long roleId);
    void upsertDataScope(Long roleId, String scopeType);
}
