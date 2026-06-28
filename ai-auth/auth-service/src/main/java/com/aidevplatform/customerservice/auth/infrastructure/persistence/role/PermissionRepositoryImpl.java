package com.aidevplatform.customerservice.auth.infrastructure.persistence.role;

import com.aidevplatform.customerservice.auth.domain.model.role.Permission;
import com.aidevplatform.customerservice.auth.domain.repository.IPermissionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 接口权限仓储实现。
 * 只读仓储，将 PermissionDO 转换为 Permission 值对象。
 */
@Repository
public class PermissionRepositoryImpl implements IPermissionRepository {

    private final PermissionMapper permissionMapper;

    public PermissionRepositoryImpl(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    @Override
    public List<Permission> findAll() {
        return permissionMapper.selectList(null).stream()
                .filter(Objects::nonNull)
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Permission> findByUserId(Long userId) {
        return permissionMapper.findPermissionsByUserId(userId).stream()
                .filter(Objects::nonNull)
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    private Permission toDomain(PermissionDO entity) {
        return Permission.of(
                entity.getId(),
                entity.getPermissionKey(),
                entity.getPermissionName(),
                entity.getModule());
    }
}
