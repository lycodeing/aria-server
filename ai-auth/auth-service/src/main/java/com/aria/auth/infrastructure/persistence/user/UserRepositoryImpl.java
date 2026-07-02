package com.aria.auth.infrastructure.persistence.user;

import com.aria.auth.domain.model.user.*;
import com.aria.auth.domain.model.user.*;
import com.aria.auth.domain.repository.IUserRepository;
import com.aria.auth.infrastructure.persistence.role.PermissionMapper;
import com.aria.auth.infrastructure.persistence.role.RoleDO;
import com.aria.auth.infrastructure.persistence.role.RoleMapper;
import com.aria.auth.application.query.UserPageQuery;
import com.aria.common.core.page.PageResult;
import com.aria.common.core.page.PageUtil;
import org.springframework.util.StringUtils;
import com.aria.common.core.util.JsonUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户仓储实现。
 * 负责 User 聚合根与 UserDO 持久化对象之间的双向转换，
 * 使用 User.reconstitute() 重建聚合根，无任何反射操作。
 */
@Repository
public class UserRepositoryImpl implements IUserRepository {

    private final UserMapper mapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    public UserRepositoryImpl(UserMapper mapper, RoleMapper roleMapper,
                               PermissionMapper permissionMapper) {
        this.mapper = mapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
    }

    @Override
    public User save(User user) {
        UserDO userDO = toDO(user);
        if (userDO.getId() != null) {
            // 主键已知：优先更新，0 行受影响说明记录不存在则插入
            // 注意：不能在 @Transactional 内用 insert-then-catch 模式，
            // PostgreSQL 在 insert 失败后会将事务置为 aborted 状态，
            // 导致 catch 块内的 updateById 抛出 25P02 (in_failed_sql_transaction)
            int updated = mapper.updateById(userDO);
            if (updated == 0) {
                mapper.insert(userDO);
            }
        } else {
            mapper.insert(userDO);
        }
        return user;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(mapper.selectById(id.getValue())).map(this::toDomain);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(mapper.selectOne(
                new LambdaQueryWrapper<UserDO>()
                        .eq(UserDO::getUsername, username)))
                .map(this::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(mapper.selectOne(
                new LambdaQueryWrapper<UserDO>()
                        .eq(UserDO::getEmail, email)))
                .map(this::toDomain);
    }

    @Override
    public boolean existsByUsername(String username) {
        return mapper.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return mapper.existsByEmail(email);
    }

    @Override
    public List<String> findRoleKeysByUserId(Long userId) {
        if (userId == null) return List.of();
        return roleMapper.findRolesByUserId(userId).stream()
                .map(RoleDO::getRoleKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> findPermissionKeysByUserId(Long userId) {
        if (userId == null) return List.of();
        return permissionMapper.findPermissionsByUserId(userId).stream()
                .map(p -> p.getPermissionKey())
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public PageResult<User> search(UserPageQuery query) {
        LambdaQueryWrapper<UserDO> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            qw.and(w -> w.like(UserDO::getUsername, kw)
                    .or().like(UserDO::getDisplayName, kw)
                    .or().like(UserDO::getEmail, kw));
        }
        qw.orderByDesc(UserDO::getId);
        Page<UserDO> result = mapper.selectPage(PageUtil.toMpPage(query), qw);

        if (result.getRecords().isEmpty()) {
            return PageUtil.toPageResult(result, this::toDomain, query);
        }

        // 批量预加载角色，消除 toDomain 中每条记录单独查角色的 N+1 问题
        List<Long> userIds = result.getRecords().stream().map(UserDO::getId).toList();
        Map<Long, Set<Long>> roleIdsByUser = roleMapper.findRolesByUserIds(userIds).stream()
                .filter(r -> r.getUserId() != null && r.getId() != null)
                .collect(Collectors.groupingBy(
                        RoleDO::getUserId,
                        Collectors.mapping(RoleDO::getId, Collectors.toCollection(HashSet::new))));

        return PageUtil.toPageResult(result, userDO -> toDomainWithRoles(userDO, roleIdsByUser), query);
    }

    /**
     * 使用预加载的角色 Map 重建聚合根，避免 toDomain 中的单条角色查询。
     */
    private User toDomainWithRoles(UserDO userDO, Map<Long, Set<Long>> roleIdsByUser) {
        List<String> history = (userDO.getPasswordHistoryJson() != null
                && !userDO.getPasswordHistoryJson().isBlank())
                ? JsonUtils.parseList(userDO.getPasswordHistoryJson(), String.class)
                : new ArrayList<>();

        Set<Long> roleIds = roleIdsByUser.getOrDefault(userDO.getId(), new HashSet<>());
        UserStatus userStatus = parseUserStatus(userDO.getStatus());
        AuthProvider authProvider = parseAuthProvider(userDO.getProvider());

        return User.reconstitute(
                UserId.of(userDO.getId()),
                userDO.getUsername(),
                userDO.getDisplayName(),
                userDO.getEmail(),
                userDO.getPhone(),
                Password.fromHash(userDO.getPasswordHash()),
                userStatus,
                roleIds,
                authProvider,
                userDO.getLoginFailCount() != null ? userDO.getLoginFailCount() : 0,
                userDO.getLockedUntil() != null
                        ? userDO.getLockedUntil().toInstant(ZoneOffset.UTC) : null,
                userDO.getPasswordChangedAt() != null
                        ? userDO.getPasswordChangedAt().toInstant(ZoneOffset.UTC) : null,
                userDO.getLastLoginAt() != null
                        ? userDO.getLastLoginAt().toInstant(ZoneOffset.UTC) : null,
                userDO.getLastLoginIp(),
                history,
                Boolean.TRUE.equals(userDO.getMustChangePassword())
        );
    }

    @Override
    public void delete(UserId id) {
        mapper.deleteById(id.getValue());
    }

    // -------------------------------------------------------
    // 内部转换（无反射）
    // -------------------------------------------------------

    private UserDO toDO(User user) {
        UserDO userDO = new UserDO();
        if (user.getId() != null) {
            userDO.setId(user.getId().getValue());
        }
        userDO.setUsername(user.getUsername());
        userDO.setDisplayName(user.getDisplayName());
        userDO.setEmail(user.getEmail());
        userDO.setPhone(user.getPhone());
        userDO.setPasswordHash(user.getPassword().hash());
        userDO.setStatus(user.getStatus().name().toLowerCase());
        userDO.setProvider(user.getProvider().name().toLowerCase());
        userDO.setLoginFailCount(user.getLoginFailCount());
        userDO.setMustChangePassword(user.isMustChangePassword());
        userDO.setPasswordHistoryJson(user.getPasswordHistory() != null
                ? JsonUtils.toJsonString(user.getPasswordHistory()) : "[]");
        if (user.getLockedUntil() != null) {
            userDO.setLockedUntil(user.getLockedUntil().atOffset(ZoneOffset.UTC).toLocalDateTime());
        }
        if (user.getPasswordChangedAt() != null) {
            userDO.setPasswordChangedAt(
                    user.getPasswordChangedAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
        }
        if (user.getLastLoginAt() != null) {
            userDO.setLastLoginAt(user.getLastLoginAt().atOffset(ZoneOffset.UTC).toLocalDateTime());
        }
        userDO.setLastLoginIp(user.getLastLoginIp());
        return userDO;
    }

    /**
     * 将持久化对象重建为 User 聚合根，使用 reconstitute() 静态工厂，无反射操作。
     *
     * @param userDO 持久化数据对象
     * @return 重建的 User 聚合根
     */
    private User toDomain(UserDO userDO) {
        List<String> history = (userDO.getPasswordHistoryJson() != null
                && !userDO.getPasswordHistoryJson().isBlank())
                ? JsonUtils.parseList(userDO.getPasswordHistoryJson(), String.class)
                : new ArrayList<>();

        Set<Long> roleIds = roleMapper.findRolesByUserId(userDO.getId()).stream()
                .map(RoleDO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        // 对 status/provider 进行判空保护，防止脏数据导致 NPE 或 IllegalArgumentException
        UserStatus userStatus = parseUserStatus(userDO.getStatus());
        AuthProvider authProvider = parseAuthProvider(userDO.getProvider());

        return User.reconstitute(
                UserId.of(userDO.getId()),
                userDO.getUsername(),
                userDO.getDisplayName(),
                userDO.getEmail(),
                userDO.getPhone(),
                Password.fromHash(userDO.getPasswordHash()),
                userStatus,
                roleIds,
                authProvider,
                userDO.getLoginFailCount() != null ? userDO.getLoginFailCount() : 0,
                userDO.getLockedUntil() != null
                        ? userDO.getLockedUntil().toInstant(ZoneOffset.UTC) : null,
                userDO.getPasswordChangedAt() != null
                        ? userDO.getPasswordChangedAt().toInstant(ZoneOffset.UTC) : null,
                userDO.getLastLoginAt() != null
                        ? userDO.getLastLoginAt().toInstant(ZoneOffset.UTC) : null,
                userDO.getLastLoginIp(),
                history,
                Boolean.TRUE.equals(userDO.getMustChangePassword())
        );
    }

    /**
     * 安全解析用户状态枚举，值为空或非法时默认返回 ACTIVE，避免 NPE。
     */
    private UserStatus parseUserStatus(String status) {
        if (status == null || status.isBlank()) {
            return UserStatus.ACTIVE;
        }
        try {
            return UserStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UserStatus.ACTIVE;
        }
    }

    /**
     * 安全解析认证提供方枚举，值为空或非法时默认返回 LOCAL，避免 NPE。
     */
    private AuthProvider parseAuthProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return AuthProvider.LOCAL;
        }
        try {
            return AuthProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AuthProvider.LOCAL;
        }
    }
}
