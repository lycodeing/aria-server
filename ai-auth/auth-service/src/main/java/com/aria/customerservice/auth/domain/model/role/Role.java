package com.aria.customerservice.auth.domain.model.role;

/**
 * 角色领域模型。
 * 无 setter，所有状态变更通过行为方法完成，保证不变量。
 */
public class Role {

    private Long id;
    private String roleKey;
    private String roleName;
    private boolean system;
    private String status;

    private Role() {}

    /**
     * 新建角色（业务创建时使用，ID 由持久化层回填）。
     */
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

    /**
     * 从持久化状态重建（Repository 专用，不做业务校验）。
     */
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

    /**
     * 重命名角色（系统内置角色不允许改名）。
     */
    public void rename(String newName) {
        if (this.system) throw new IllegalStateException("系统内置角色不允许修改名称");
        if (newName == null || newName.isBlank()) throw new IllegalArgumentException("角色名称不能为空");
        this.roleName = newName;
    }

    /** 启用角色 */
    public void activate() {
        this.status = "active";
    }

    /** 停用角色（系统内置角色不允许停用） */
    public void deactivate() {
        if (this.system) throw new IllegalStateException("系统内置角色不允许停用");
        this.status = "inactive";
    }

    public Long getId() { return id; }
    public String getRoleKey() { return roleKey; }
    public String getRoleName() { return roleName; }
    public boolean isSystem() { return system; }
    public String getStatus() { return status; }

    /**
     * 仅 Repository 实现层在 insert 后回填自增 ID 时使用。
     */
    public void assignId(Long id) { this.id = id; }
}
