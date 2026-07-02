package com.aria.auth.infrastructure.persistence.role;

import com.aria.auth.application.query.RolePageQuery;
import com.aria.auth.domain.model.role.Role;
import com.aria.auth.domain.repository.IRoleRepository;
import com.aria.common.core.page.PageResult;
import com.aria.common.core.page.PageUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 角色仓储实现。
 * 使用 Role.reconstitute() 重建领域对象，无反射操作，严格隔离持久化细节。
 */
@Repository
public class RoleRepositoryImpl implements IRoleRepository {

    private final RoleMapper roleMapper;

    public RoleRepositoryImpl(RoleMapper roleMapper) {
        this.roleMapper = roleMapper;
    }

    @Override
    public Role save(Role role) {
        RoleDO entity = toEntity(role);
        if (role.getId() == null) {
            roleMapper.insert(entity);
            // 回填自增 ID
            role.assignId(entity.getId());
        } else {
            roleMapper.updateById(entity);
        }
        return role;
    }

    @Override
    public Optional<Role> findById(Long id) {
        return Optional.ofNullable(roleMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public boolean existsByRoleKey(String roleKey) {
        return roleMapper.selectCount(
                new LambdaQueryWrapper<RoleDO>().eq(RoleDO::getRoleKey, roleKey)) > 0;
    }

    @Override
    public PageResult<Role> search(RolePageQuery query) {
        LambdaQueryWrapper<RoleDO> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            qw.and(w -> w.like(RoleDO::getRoleKey, kw).or().like(RoleDO::getRoleName, kw));
        }
        qw.orderByDesc(RoleDO::getId);
        return PageUtil.toPageResult(
            roleMapper.selectPage(PageUtil.toMpPage(query), qw),
            this::toDomain, query);
    }

    @Override
    public void delete(Long id) {
        roleMapper.deleteById(id);
    }

    @Override
    public List<Role> findByUserId(Long userId) {
        if (userId == null) return Collections.emptyList();
        return roleMapper.findRolesByUserId(userId).stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        // 先清后批量插入：单条 SQL 多 VALUES，替代原来 N 次单行 INSERT
        roleMapper.deleteRolePermissions(roleId);
        List<Long> ids = permissionIds != null ? permissionIds : Collections.emptyList();
        if (!ids.isEmpty()) {
            roleMapper.insertRolePermissions(roleId, ids);
        }
    }

    @Override
    public String findDataScope(Long roleId) {
        String scope = roleMapper.findScopeTypeByRoleId(roleId);
        return scope != null ? scope : "SELF";
    }

    @Override
    public void upsertDataScope(Long roleId, String scopeType) {
        roleMapper.upsertDataScope(roleId, scopeType);
    }

    // -------------------------------------------------------
    // 内部转换（无反射）
    // -------------------------------------------------------

    private Role toDomain(RoleDO entity) {
        return Role.reconstitute(
                entity.getId(),
                entity.getRoleKey(),
                entity.getRoleName(),
                Boolean.TRUE.equals(entity.getIsSystem()),
                entity.getStatus());
    }

    private RoleDO toEntity(Role role) {
        RoleDO entity = new RoleDO();
        if (role.getId() != null) entity.setId(role.getId());
        entity.setRoleKey(role.getRoleKey());
        entity.setRoleName(role.getRoleName());
        entity.setIsSystem(role.isSystem());
        entity.setStatus(role.getStatus());
        return entity;
    }
}
