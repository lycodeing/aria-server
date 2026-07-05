package com.aria.auth.domain.model.role;

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

    public Long getId() {
        return id;
    }

    public String getPermissionKey() {
        return permissionKey;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public String getModule() {
        return module;
    }
}
