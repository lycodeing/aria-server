# Auth Service DDD 架构修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 auth-service 中所有违反 DDD 架构的问题，使其与 knowledge-service 的层次规范一致。

**Architecture:** 六边形架构（Hexagonal Architecture）+ DDD 战术模式。Domain 层不依赖任何框架；Application 层只依赖 Domain 接口；Infrastructure 层实现 Domain 接口；Interfaces 层通过 Assembler 完成 VO 转换。

**Tech Stack:** Spring Boot 3、MyBatis-Plus、Sa-Token、Lombok、Jakarta Validation

**修复范围（按严重度）：**
- 🔴 User 聚合根缺 `reconstitute()` / `updateProfile()` 导致两层反射 hack
- 🔴 RoleApplicationService 完全绕过 Domain 层，直接操作 Mapper/DO
- 🟠 AuthApplicationService 持有 HttpServletRequest、返回 Map、注入具体类
- 🟠 UserController 直接注入 IUserRepository 跨层
- 🟠 RoleController 持有 RoleDO 引用
- 🟡 Role/Permission 贫血（有 setter）；Controller 缺 Assembler

---

## 涉及文件一览

| 操作 | 文件路径 |
|------|---------|
| **修改** | `domain/model/user/User.java` |
| **修改** | `domain/model/role/Role.java` |
| **修改** | `domain/model/role/Permission.java` |
| **新建** | `domain/repository/IRoleRepository.java` |
| **新建** | `domain/repository/IPermissionRepository.java` |
| **修改** | `infrastructure/persistence/user/UserRepositoryImpl.java` |
| **新建** | `infrastructure/persistence/role/RoleRepositoryImpl.java` |
| **新建** | `infrastructure/persistence/role/PermissionRepositoryImpl.java` |
| **修改** | `application/command/LoginCommand.java` |
| **新建** | `application/result/LoginResult.java` |
| **修改** | `application/service/AuthApplicationService.java` |
| **修改** | `application/service/UserApplicationService.java` |
| **修改** | `application/service/RoleApplicationService.java` |
| **新建** | `interfaces/assembler/UserAssembler.java` |
| **新建** | `interfaces/assembler/RoleAssembler.java` |
| **修改** | `interfaces/rest/AuthController.java` |
| **修改** | `interfaces/rest/UserController.java` |
| **修改** | `interfaces/rest/RoleController.java` |

---

## Task 1：补全 User 聚合根

**问题：** 缺少 `reconstitute()` 静态工厂方法（从 DB 重建用不触发事件），缺少 `updateProfile()` 行为方法，`register()` 不含 `phone` 参数，导致上下两层均需反射打洞。

**文件：** 修改 `auth-service/src/main/java/com/aidevplatform/customerservice/auth/domain/model/user/User.java`

- [ ] **步骤 1：在 `register()` 参数列表中加入 `phone`，添加 `updateProfile()` 和 `reconstitute()` 方法**

将 `User.java` 的 `register()` 签名改为含 `phone`，并在 `// ===== 登录相关 =====` 之前插入新方法：

```java
// ===== 工厂方法 =====

public static User register(UserId id, String username, String displayName, String email,
                            String phone, Password password, Set<Long> roleIds, AuthProvider provider) {
    validateUsername(username);
    validateEmail(email);
    User u = new User();
    u.id = id;
    u.username = username;
    u.displayName = displayName;
    u.email = email;
    u.phone = phone;
    u.password = password;
    u.status = UserStatus.ACTIVE;
    u.roleIds = new HashSet<>(roleIds);
    u.provider = provider;
    u.loginFailCount = 0;
    u.passwordHistory = new ArrayList<>();
    u.passwordHistory.add(password.hash());
    u.passwordChangedAt = Instant.now();
    u.mustChangePassword = true;
    u.registerEvent(new UserRegistered(id, username, email, provider));
    return u;
}

/**
 * 从持久化状态重建聚合根（不触发领域事件）。
 * 仅供 Repository 实现层调用。
 */
public static User reconstitute(UserId id, String username, String displayName, String email,
                                String phone, Password password, UserStatus status,
                                Set<Long> roleIds, AuthProvider provider,
                                int loginFailCount, Instant lockedUntil,
                                Instant passwordChangedAt, Instant lastLoginAt,
                                String lastLoginIp, List<String> passwordHistory,
                                boolean mustChangePassword) {
    User u = new User();
    u.id = id;
    u.username = username;
    u.displayName = displayName;
    u.email = email;
    u.phone = phone;
    u.password = password;
    u.status = status;
    u.roleIds = new HashSet<>(roleIds);
    u.provider = provider;
    u.loginFailCount = loginFailCount;
    u.lockedUntil = lockedUntil;
    u.passwordChangedAt = passwordChangedAt;
    u.lastLoginAt = lastLoginAt;
    u.lastLoginIp = lastLoginIp;
    u.passwordHistory = passwordHistory != null ? new ArrayList<>(passwordHistory) : new ArrayList<>();
    u.mustChangePassword = mustChangePassword;
    return u;
}
```

- [ ] **步骤 2：在 `// ===== 状态管理 =====` 之前添加 `updateProfile()` 方法**

```java
// ===== 资料更新 =====

/**
 * 更新用户基本资料。
 * 邮箱唯一性校验由应用服务在调用前完成（需查库），聚合根只做格式校验。
 */
public void updateProfile(String displayName, String email, String phone) {
    if (displayName != null && !displayName.isBlank()) {
        this.displayName = displayName;
    }
    if (email != null && !email.isBlank() && !email.equals(this.email)) {
        validateEmail(email);
        this.email = email;
    }
    if (phone != null) {
        this.phone = phone;
    }
}
```

---

## Task 2：UserRepositoryImpl 去反射

**问题：** `toDomain()` 用反射重建聚合根，根本原因是 Task 1 缺 `reconstitute()`，现在直接替换。

**文件：** 修改 `infrastructure/persistence/user/UserRepositoryImpl.java`

- [ ] **步骤 1：将 `toDomain()` 方法替换为调用 `User.reconstitute()`**

```java
private User toDomain(UserDO do_) {
    List<String> history = do_.getPasswordHistoryJson() != null && !do_.getPasswordHistoryJson().isBlank()
            ? JsonUtils.parseList(do_.getPasswordHistoryJson(), String.class)
            : new ArrayList<>();
    Set<Long> roleIds = roleMapper.findRolesByUserId(do_.getId()).stream()
            .map(RoleDO::getId).filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));

    return User.reconstitute(
            UserId.of(do_.getId()),
            do_.getUsername(),
            do_.getDisplayName(),
            do_.getEmail(),
            do_.getPhone(),
            Password.fromHash(do_.getPasswordHash()),
            UserStatus.valueOf(do_.getStatus().toUpperCase()),
            roleIds,
            AuthProvider.valueOf(do_.getProvider().toUpperCase()),
            do_.getLoginFailCount() != null ? do_.getLoginFailCount() : 0,
            do_.getLockedUntil() != null ? do_.getLockedUntil().toInstant(ZoneOffset.UTC) : null,
            do_.getPasswordChangedAt() != null ? do_.getPasswordChangedAt().toInstant(ZoneOffset.UTC) : null,
            do_.getLastLoginAt() != null ? do_.getLastLoginAt().toInstant(ZoneOffset.UTC) : null,
            do_.getLastLoginIp(),
            history,
            Boolean.TRUE.equals(do_.getMustChangePassword())
    );
}
```

- [ ] **步骤 2：删除 `setField()` 辅助方法（反射工具）**

删除整个：
```java
private void setField(Object target, String fieldName, Object value) {
    try { var field = target.getClass().getDeclaredField(fieldName); field.setAccessible(true); field.set(target, value); }
    catch (Exception e) { throw new RuntimeException("设置字段失败: " + fieldName, e); }
}
```

- [ ] **步骤 3：构建验证（仅编译，不运行）**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice/ai-customerservice-backend/ai-auth
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile -pl auth-service -am -q
```

预期：BUILD SUCCESS，无反射相关警告。

---

## Task 3：UserApplicationService 去反射

**问题：** `create()` 和 `updateProfile()` 用反射写字段，`resetPassword()` 也绕过聚合根。

**文件：** 修改 `application/service/UserApplicationService.java`

- [ ] **步骤 1：修复 `create()` — 传入 phone，调用含 phone 的 `register()`**

```java
@Transactional
public User create(String username, String displayName, String email,
                   String phone, String password) {
    if (userRepo.existsByUsername(username)) {
        throw BusinessException.of("AUTH_USERNAME_EXISTS", "用户名已存在");
    }
    if (email != null && !email.isBlank() && userRepo.existsByEmail(email)) {
        throw BusinessException.of("AUTH_EMAIL_EXISTS", "邮箱已存在");
    }
    passwordPolicy.check(password);
    Password pwd = Password.encode(password, passwordHasher);
    User user = User.register(
            UserId.of(IdGenerator.nextId()),
            username, displayName, email, phone,
            pwd, Set.of(), AuthProvider.LOCAL);
    userRepo.save(user);
    return user;
}
```

- [ ] **步骤 2：修复 `updateProfile()` — 调用聚合根方法**

```java
@Transactional
public User updateProfile(Long id, String displayName, String email, String phone) {
    User user = load(id);
    if (email != null && !email.isBlank() && !email.equals(user.getEmail())) {
        if (userRepo.existsByEmail(email)) {
            throw BusinessException.of("AUTH_EMAIL_EXISTS", "邮箱已存在");
        }
    }
    user.updateProfile(displayName, email, phone);
    userRepo.save(user);
    return user;
}
```

- [ ] **步骤 3：修复 `resetPassword()` — 调用聚合根方法**

```java
@Transactional
public void resetPassword(Long id, String newPassword) {
    User user = load(id);
    passwordPolicy.check(newPassword);
    user.resetPassword(Password.encode(newPassword, passwordHasher));
    userRepo.save(user);
}
```

- [ ] **步骤 4：修复依赖注入 — 将 `BCryptPasswordHasher` 改为 `PasswordHasher` 接口**

```java
// 修改前
private final BCryptPasswordHasher passwordHasher;

// 修改后
private final PasswordHasher passwordHasher;
```

构造器参数类型同步修改为 `PasswordHasher`。

- [ ] **步骤 5：删除 `setField()` 辅助方法**

- [ ] **步骤 6：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile -pl auth-service -am -q
```

预期：BUILD SUCCESS。

---

## Task 4：Role / Permission 领域模型去贫血

**问题：** `Role` 有 `setId()`，`Permission` 全是 getter/setter POJO，无任何业务行为。

**文件：** 修改 `domain/model/role/Role.java` 和 `domain/model/role/Permission.java`

- [ ] **步骤 1：重写 `Role.java`，去掉所有 setter，加 `rename()` / `activate()` / `deactivate()` 行为方法**

```java
package com.aidevplatform.customerservice.auth.domain.model.role;

/**
 * 角色领域模型。
 * 无 setter，所有状态变更通过行为方法完成。
 */
public class Role {
    private Long id;
    private String roleKey;
    private String roleName;
    private boolean system;
    private String status;

    private Role() {}

    /** 新建角色（业务创建时使用） */
    public static Role create(String roleKey, String roleName, boolean system) {
        if (roleKey == null || roleKey.isBlank()) throw new IllegalArgumentException("角色标识不能为空");
        if (roleName == null || roleName.isBlank()) throw new IllegalArgumentException("角色名称不能为空");
        Role r = new Role();
        r.roleKey = roleKey;
        r.roleName = roleName;
        r.system = system;
        r.status = "active";
        return r;
    }

    /** 从持久化状态重建（Repository 专用，不做业务校验） */
    public static Role reconstitute(Long id, String roleKey, String roleName,
                                    boolean system, String status) {
        Role r = new Role();
        r.id = id;
        r.roleKey = roleKey;
        r.roleName = roleName;
        r.system = system;
        r.status = status;
        return r;
    }

    /** 重命名角色（系统内置角色不允许改名） */
    public void rename(String newName) {
        if (this.system) throw new IllegalStateException("系统内置角色不允许修改名称");
        if (newName == null || newName.isBlank()) throw new IllegalArgumentException("角色名称不能为空");
        this.roleName = newName;
    }

    /** 启用角色 */
    public void activate() { this.status = "active"; }

    /** 停用角色 */
    public void deactivate() {
        if (this.system) throw new IllegalStateException("系统内置角色不允许停用");
        this.status = "inactive";
    }

    public Long getId() { return id; }
    public String getRoleKey() { return roleKey; }
    public String getRoleName() { return roleName; }
    public boolean isSystem() { return system; }
    public String getStatus() { return status; }

    /** 仅 Repository 实现层在 insert 后回填 ID 时使用 */
    void assignId(Long id) { this.id = id; }
}
```

- [ ] **步骤 2：重写 `Permission.java`，去掉 setter，改为全参静态工厂**

```java
package com.aidevplatform.customerservice.auth.domain.model.role;

/**
 * 接口权限值对象（只读，无行为变更）。
 */
public class Permission {
    private final Long id;
    private final String permissionKey;
    private final String permissionName;
    private final String module;

    private Permission(Long id, String permissionKey, String permissionName, String module) {
        this.id = id;
        this.permissionKey = permissionKey;
        this.permissionName = permissionName;
        this.module = module;
    }

    public static Permission of(Long id, String permissionKey,
                                String permissionName, String module) {
        return new Permission(id, permissionKey, permissionName, module);
    }

    public Long getId() { return id; }
    public String getPermissionKey() { return permissionKey; }
    public String getPermissionName() { return permissionName; }
    public String getModule() { return module; }
}
```

---

## Task 5：新建 IRoleRepository / IPermissionRepository

**文件：** 新建 `domain/repository/IRoleRepository.java`，新建 `domain/repository/IPermissionRepository.java`

- [ ] **步骤 1：创建 `IRoleRepository.java`**

```java
package com.aidevplatform.customerservice.auth.domain.repository;

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
    PageResult<Role> search(String keyword, int page, int size);
    void delete(Long id);
    List<Role> findByUserId(Long userId);
    void assignPermissions(Long roleId, List<Long> permissionIds);
    String findDataScope(Long roleId);
    void upsertDataScope(Long roleId, String scopeType);
}
```

- [ ] **步骤 2：创建 `IPermissionRepository.java`**

```java
package com.aidevplatform.customerservice.auth.domain.repository;

import com.aidevplatform.customerservice.auth.domain.model.role.Permission;

import java.util.List;

/**
 * 接口权限仓储接口（Domain 层定义，Infrastructure 层实现）。
 */
public interface IPermissionRepository {
    List<Permission> findAll();
    List<Permission> findByUserId(Long userId);
}
```

---

## Task 6：新建 RoleRepositoryImpl / PermissionRepositoryImpl

**文件：** 新建 `infrastructure/persistence/role/RoleRepositoryImpl.java`，新建 `infrastructure/persistence/role/PermissionRepositoryImpl.java`

- [ ] **步骤 1：创建 `RoleRepositoryImpl.java`**

```java
package com.aidevplatform.customerservice.auth.infrastructure.persistence.role;

import com.aidevplatform.customerservice.auth.domain.model.role.Role;
import com.aidevplatform.customerservice.auth.domain.repository.IRoleRepository;
import com.aidevplatform.common.core.exception.BusinessException;
import com.aidevplatform.common.core.exception.CommonErrorCode;
import com.aidevplatform.common.core.page.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class RoleRepositoryImpl implements IRoleRepository {

    private final RoleMapper roleMapper;

    public RoleRepositoryImpl(RoleMapper roleMapper) {
        this.roleMapper = roleMapper;
    }

    @Override
    public Role save(Role role) {
        RoleDO DO = toEntity(role);
        if (role.getId() == null) {
            roleMapper.insert(DO);
            role.assignId(DO.getId());
        } else {
            roleMapper.updateById(DO);
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
    public PageResult<Role> search(String keyword, int page, int size) {
        int p = Math.max(page, 0);
        int s = size > 0 ? size : 20;
        LambdaQueryWrapper<RoleDO> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like(RoleDO::getRoleKey, kw).or().like(RoleDO::getRoleName, kw));
        }
        qw.orderByDesc(RoleDO::getId);
        Page<RoleDO> result = roleMapper.selectPage(new Page<>(p + 1L, s), qw);
        List<Role> items = result.getRecords().stream().map(this::toDomain).collect(Collectors.toList());
        return PageResult.of(result.getTotal(), p, s, items);
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
        roleMapper.deleteRolePermissions(roleId);
        List<Long> ids = permissionIds != null ? permissionIds : Collections.emptyList();
        for (Long pid : ids) {
            roleMapper.insertRolePermission(roleId, pid);
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

    private Role toDomain(RoleDO DO) {
        return Role.reconstitute(DO.getId(), DO.getRoleKey(), DO.getRoleName(),
                Boolean.TRUE.equals(DO.getIsSystem()), DO.getStatus());
    }

    private RoleDO toEntity(Role role) {
        RoleDO DO = new RoleDO();
        if (role.getId() != null) DO.setId(role.getId());
        DO.setRoleKey(role.getRoleKey());
        DO.setRoleName(role.getRoleName());
        DO.setIsSystem(role.isSystem());
        DO.setStatus(role.getStatus());
        return DO;
    }
}
```

- [ ] **步骤 2：创建 `PermissionRepositoryImpl.java`**

```java
package com.aidevplatform.customerservice.auth.infrastructure.persistence.role;

import com.aidevplatform.customerservice.auth.domain.model.role.Permission;
import com.aidevplatform.customerservice.auth.domain.repository.IPermissionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Repository
public class PermissionRepositoryImpl implements IPermissionRepository {

    private final PermissionMapper permissionMapper;

    public PermissionRepositoryImpl(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    @Override
    public List<Permission> findAll() {
        return permissionMapper.selectList(null).stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Permission> findByUserId(Long userId) {
        return permissionMapper.findPermissionsByUserId(userId).stream()
                .filter(Objects::nonNull)
                .map(this::toDomain).collect(Collectors.toList());
    }

    private Permission toDomain(PermissionDO DO) {
        return Permission.of(DO.getId(), DO.getPermissionKey(),
                DO.getPermissionName(), DO.getModule());
    }
}
```

---

## Task 7：重构 RoleApplicationService

**问题：** 当前直接注入 `RoleMapper` / `PermissionMapper`，完全绕过 Domain 层。改为依赖 `IRoleRepository` / `IPermissionRepository`。

**文件：** 修改 `application/service/RoleApplicationService.java`

- [ ] **步骤 1：全量替换 RoleApplicationService**

```java
package com.aidevplatform.customerservice.auth.application.service;

import com.aidevplatform.customerservice.auth.domain.model.role.Permission;
import com.aidevplatform.customerservice.auth.domain.model.role.Role;
import com.aidevplatform.customerservice.auth.domain.repository.IPermissionRepository;
import com.aidevplatform.customerservice.auth.domain.repository.IRoleRepository;
import com.aidevplatform.common.core.exception.BusinessException;
import com.aidevplatform.common.core.exception.CommonErrorCode;
import com.aidevplatform.common.core.page.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色应用服务。
 * 只依赖 Domain 层仓储接口，不接触任何基础设施对象（Mapper/DO）。
 */
@Service
public class RoleApplicationService {

    private final IRoleRepository roleRepo;
    private final IPermissionRepository permissionRepo;

    public RoleApplicationService(IRoleRepository roleRepo,
                                  IPermissionRepository permissionRepo) {
        this.roleRepo = roleRepo;
        this.permissionRepo = permissionRepo;
    }

    /** 分页查询角色列表 */
    public PageResult<Role> list(String keyword, int page, int pageSize) {
        return roleRepo.search(keyword, page, pageSize);
    }

    /** 按 ID 查询单个角色 */
    public Role getById(Long id) {
        return roleRepo.findById(id)
                .orElseThrow(() -> BusinessException.of(CommonErrorCode.NOT_FOUND, "角色"));
    }

    /** 新建角色 */
    @Transactional(rollbackFor = Exception.class)
    public Role create(String roleKey, String roleName, Boolean isSystem) {
        if (roleRepo.existsByRoleKey(roleKey)) {
            throw BusinessException.of("ROLE_KEY_EXISTS", "角色标识已存在");
        }
        Role role = Role.create(roleKey, roleName, Boolean.TRUE.equals(isSystem));
        return roleRepo.save(role);
    }

    /** 更新角色名称或状态 */
    @Transactional(rollbackFor = Exception.class)
    public Role update(Long id, String roleName, String status) {
        Role role = getById(id);
        if (roleName != null && !roleName.isBlank()) {
            role.rename(roleName);
        }
        if ("inactive".equals(status)) {
            role.deactivate();
        } else if ("active".equals(status)) {
            role.activate();
        }
        return roleRepo.save(role);
    }

    /** 删除角色（系统内置角色不允许删除） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Role role = getById(id);
        if (role.isSystem()) {
            throw BusinessException.of("ROLE_IS_SYSTEM", "系统内置角色不允许删除");
        }
        roleRepo.delete(id);
    }

    /** 查询接口权限列表（按模块分组，Controller 层负责组装 PermissionTreeVO） */
    public List<Permission> listAllPermissions() {
        return permissionRepo.findAll();
    }

    /** 给角色分配接口权限（全量替换） */
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        getById(roleId); // 校验角色存在
        roleRepo.assignPermissions(roleId, permissionIds);
    }

    /** 查询角色数据权限范围 */
    public String getDataScope(Long roleId) {
        return roleRepo.findDataScope(roleId);
    }

    /** 设置角色数据权限范围 */
    @Transactional(rollbackFor = Exception.class)
    public void setDataScope(Long roleId, String scopeType) {
        getById(roleId); // 校验角色存在
        roleRepo.upsertDataScope(roleId, scopeType);
    }
}
```

- [ ] **步骤 2：编译验证**

```bash
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile -pl auth-service -am -q
```

预期：BUILD SUCCESS。

---

## Task 8：修复 AuthApplicationService

**问题：** `HttpServletRequest` 渗透应用层；返回值是 `Map<String, Object>`；注入具体类 `BCryptPasswordHasher`。

**文件：** 修改 `application/command/LoginCommand.java`；新建 `application/result/LoginResult.java`；修改 `application/service/AuthApplicationService.java`

- [ ] **步骤 1：在 LoginCommand 中加入 `clientIp` 字段**

```java
package com.aidevplatform.customerservice.auth.application.command;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/** 登录命令，包含所有登录所需信息，不携带 HTTP 关注点。 */
@Getter
public class LoginCommand {

    @NotBlank(message = "用户名不能为空")
    private final String username;

    @NotBlank(message = "密码不能为空")
    private final String password;

    private final boolean rememberMe;

    /** 客户端 IP，由 Controller 层从 HttpServletRequest 提取后传入 */
    private final String clientIp;

    public LoginCommand(String username, String password,
                        boolean rememberMe, String clientIp) {
        this.username = username;
        this.password = password;
        this.rememberMe = rememberMe;
        this.clientIp = clientIp;
    }
}
```

- [ ] **步骤 2：新建 `application/result/LoginResult.java`**

```java
package com.aidevplatform.customerservice.auth.application.result;

import java.util.List;

/** 登录用例输出结果（强类型，替代 Map<String, Object>）。 */
public class LoginResult {

    private final String tokenName;
    private final String tokenValue;
    private final long expiresIn;
    private final long userId;
    private final String username;
    private final String displayName;
    private final List<String> roles;
    private final boolean mustChangePassword;

    public LoginResult(String tokenName, String tokenValue, long expiresIn,
                       long userId, String username, String displayName,
                       List<String> roles, boolean mustChangePassword) {
        this.tokenName = tokenName;
        this.tokenValue = tokenValue;
        this.expiresIn = expiresIn;
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.roles = roles;
        this.mustChangePassword = mustChangePassword;
    }

    public String getTokenName() { return tokenName; }
    public String getTokenValue() { return tokenValue; }
    public long getExpiresIn() { return expiresIn; }
    public long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public List<String> getRoles() { return roles; }
    public boolean isMustChangePassword() { return mustChangePassword; }
}
```

- [ ] **步骤 3：修改 AuthApplicationService — 去掉 HttpServletRequest，返回 LoginResult，改用 PasswordHasher 接口**

将 `login()` 签名改为 `public LoginResult login(LoginCommand cmd)`，并将构造器依赖从 `BCryptPasswordHasher` 改为 `PasswordHasher`：

```java
// 依赖改为接口
private final PasswordHasher passwordHasher;

public AuthApplicationService(IUserRepository userRepo,
                               PasswordHasher passwordHasher,
                               LoginRateLimiter rateLimiter,
                               LoginAttemptPolicy attemptPolicy,
                               SsoCookieWriter ssoCookieWriter) { ... }

@Transactional
public LoginResult login(LoginCommand cmd) {
    String ip = cmd.getClientIp();
    // IP 频控
    if (!rateLimiter.tryAcquire(ip)) {
        throw BusinessException.of(CommonErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试");
    }
    // 查用户
    User user = userRepo.findByUsername(cmd.getUsername())
            .orElseThrow(this::invalidCredential);
    // 校验状态
    if (!user.canLogin()) {
        if (user.getStatus() == UserStatus.DISABLED)
            throw BusinessException.of("AUTH_ACCOUNT_DISABLED", "账号已被禁用");
        throw BusinessException.of("AUTH_ACCOUNT_LOCKED", "账号已锁定，请稍后再试");
    }
    // 校验密码
    if (!user.getPassword().matches(cmd.getPassword(), passwordHasher)) {
        user.onLoginFailed(attemptPolicy.getMaxFailCount(), attemptPolicy.getLockDurationMinutes());
        userRepo.save(user);
        rateLimiter.recordFailure(ip);
        throw invalidCredential();
    }
    // 登录成功
    user.onLoginSucceeded(ip);
    userRepo.save(user);
    List<String> roleKeys = userRepo.findRoleKeysByUserId(user.getId().getValue());
    long timeout = cmd.isRememberMe() ? 30 * 86400L : 28800L;
    StpUtil.login(user.getId().getValue(), new SaLoginModel()
            .setTimeout(timeout)
            .setExtra("username", user.getUsername())
            .setExtra("displayName", user.getDisplayName())
            .setExtra("roles", roleKeys));
    // SSO Cookie（仍在应用服务内，因为 SsoCookieWriter 是基础设施端口）
    try {
        var attrs = (org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
        var resp = attrs.getResponse();
        if (resp != null) ssoCookieWriter.writeTokenCookie(
                resp, "Authorization", StpUtil.getTokenValue(), (int) timeout);
    } catch (Exception ignored) {}

    return new LoginResult(
            "Authorization", StpUtil.getTokenValue(), timeout,
            user.getId().getValue(), user.getUsername(),
            user.getDisplayName(), roleKeys, user.isMustChangePassword());
}
```

---

## Task 9：修复 UserController

**问题：** 直接注入 `IUserRepository`，`/me` GET 绕过应用层；`toVO()` 内联缺 Assembler。

**文件：** 修改 `interfaces/rest/UserController.java`

- [ ] **步骤 1：删除 `IUserRepository` 注入，将 `/me` GET 查询下移到 `UserApplicationService`**

在 `UserApplicationService` 中补充：
```java
public User getCurrentUser(Long id) {
    return load(id);
}
```

`UserController` 中 `me()` 方法改为：
```java
@GetMapping("/me")
public R<UserVO> me() {
    Long uid = StpUtil.getLoginIdAsLong();
    User user = userAppService.getCurrentUser(uid);
    return R.ok(UserAssembler.toVO(user));
}
```

- [ ] **步骤 2：去掉构造器中的 `IUserRepository` 参数**

```java
public UserController(UserApplicationService userAppService) {
    this.userAppService = userAppService;
}
```

- [ ] **步骤 3：将所有 `toVO(user)` 调用替换为 `UserAssembler.toVO(user)`**（Task 11 中创建 Assembler 后生效）

---

## Task 10：修复 RoleController

**问题：** `create()` / `update()` 接收 `RoleDO` 返回值；`list()` 通过 `Map` 转换；`toRoleVoMap()` 在应用服务里。

**文件：** 修改 `interfaces/rest/RoleController.java`

- [ ] **步骤 1：将 `create()` / `update()` 改为接收 `Role` 领域对象，通过 `RoleAssembler` 转 VO**

```java
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

@GetMapping("/{id}")
public R<RoleVO> getById(@PathVariable Long id) {
    return R.ok(RoleAssembler.toVO(roleAppService.getById(id)));
}
```

- [ ] **步骤 2：将 `list()` 改为使用 `PageResult<Role>`**

```java
@GetMapping
public R<PageVO<RoleVO>> list(
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize) {
    PageResult<Role> result = roleAppService.list(keyword, page, pageSize);
    List<RoleVO> vos = result.items().stream().map(RoleAssembler::toVO).toList();
    return R.ok(new PageVO<>(vos, result.total()));
}
```

- [ ] **步骤 3：将 `permissionTree()` 改为使用 `Permission` 领域对象**

```java
@GetMapping("/permissions/tree")
public R<List<PermissionTreeVO>> permissionTree() {
    List<Permission> all = roleAppService.listAllPermissions();
    // 按 module 分组组装 PermissionTreeVO
    Map<String, List<Permission>> grouped = all.stream()
            .filter(p -> p.getModule() != null)
            .collect(Collectors.groupingBy(Permission::getModule,
                     LinkedHashMap::new, Collectors.toList()));
    List<PermissionTreeVO> tree = grouped.entrySet().stream()
            .map(e -> new PermissionTreeVO(e.getKey(),
                    e.getValue().stream().map(p ->
                            new PermissionItemVO(p.getPermissionKey(), p.getPermissionName()))
                    .toList()))
            .toList();
    return R.ok(tree);
}
```

- [ ] **步骤 4：删除 `import RoleDO`，去掉私有 `toRoleVO(Map)` 方法**

---

## Task 11：新建 UserAssembler / RoleAssembler

**文件：** 新建 `interfaces/assembler/UserAssembler.java`；新建 `interfaces/assembler/RoleAssembler.java`

- [ ] **步骤 1：创建 `UserAssembler.java`**

```java
package com.aidevplatform.customerservice.auth.interfaces.assembler;

import com.aidevplatform.customerservice.auth.domain.model.user.User;
import com.aidevplatform.customerservice.auth.interfaces.rest.vo.UserVO;

/** 用户领域对象 ↔ 接口层 VO 转换器。 */
public final class UserAssembler {

    private UserAssembler() {}

    public static UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId().getValue());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus().name().toLowerCase());
        vo.setProvider(user.getProvider().name());
        if (user.getLastLoginAt() != null) {
            vo.setLastLoginAt(user.getLastLoginAt().toString());
        }
        return vo;
    }
}
```

- [ ] **步骤 2：创建 `RoleAssembler.java`**

```java
package com.aidevplatform.customerservice.auth.interfaces.assembler;

import com.aidevplatform.customerservice.auth.domain.model.role.Role;
import com.aidevplatform.customerservice.auth.interfaces.rest.vo.RoleVO;

/** 角色领域对象 ↔ 接口层 VO 转换器。 */
public final class RoleAssembler {

    private RoleAssembler() {}

    public static RoleVO toVO(Role role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setRoleKey(role.getRoleKey());
        vo.setRoleName(role.getRoleName());
        vo.setIsSystem(role.isSystem());
        vo.setStatus(role.getStatus());
        return vo;
    }
}
```

- [ ] **步骤 3：修复 AuthController — 使用 LoginResult 强类型**

`AuthController.login()` 改为：
```java
@PostMapping("/login")
public R<LoginResultVO> login(@RequestBody LoginRequest req, HttpServletRequest request) {
    String clientIp = extractIp(request);
    LoginCommand cmd = new LoginCommand(req.getUsername(), req.getPassword(),
                                        req.isRememberMe(), clientIp);
    LoginResult result = authService.login(cmd);
    return R.ok(toLoginResultVO(result));
}

private String extractIp(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (ip == null || ip.isBlank()) ip = request.getHeader("X-Real-IP");
    if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
    return ip != null && ip.contains(",") ? ip.split(",")[0].trim() : ip;
}

private LoginResultVO toLoginResultVO(LoginResult r) {
    LoginResultVO vo = new LoginResultVO();
    vo.setTokenName(r.getTokenName());
    vo.setTokenValue(r.getTokenValue());
    vo.setExpiresIn(r.getExpiresIn());
    vo.setUserId(r.getUserId());
    vo.setUsername(r.getUsername());
    vo.setDisplayName(r.getDisplayName());
    vo.setRoles(r.getRoles());
    vo.setMustChangePassword(r.isMustChangePassword());
    return vo;
}
```

删除原来的 `Map<String, Object>` 版本 `toLoginResultVO()`。

---

## Task 12：全量构建验证

- [ ] **步骤 1：执行完整编译**

```bash
cd /Users/lycodeing/IdeaProjects/ai-customerservice/ai-customerservice-backend/ai-auth
/Users/lycodeing/apache-maven-3.9.12/bin/mvn compile -pl auth-service -am
```

预期：BUILD SUCCESS，零编译错误，零反射警告。

- [ ] **步骤 2：验证无反射 hack 残留**

```bash
grep -rn "setAccessible\|getDeclaredField\|setField" \
  ai-customerservice-backend/ai-auth/auth-service/src/main/java/
```

预期：无任何输出。

- [ ] **步骤 3：验证应用层无基础设施导入**

```bash
grep -rn "infrastructure\." \
  ai-customerservice-backend/ai-auth/auth-service/src/main/java/com/aidevplatform/customerservice/auth/application/
```

预期：无任何输出。

- [ ] **步骤 4：验证 interfaces 层无基础设施导入**

```bash
grep -rn "infrastructure\.persistence" \
  ai-customerservice-backend/ai-auth/auth-service/src/main/java/com/aidevplatform/customerservice/auth/interfaces/
```

预期：无任何输出。

