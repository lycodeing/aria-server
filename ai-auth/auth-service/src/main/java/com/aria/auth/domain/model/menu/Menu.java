package com.aria.auth.domain.model.menu;

/**
 * 菜单/按钮领域模型。
 * menuType：DIRECTORY=目录，MENU=菜单页面，BUTTON=按钮/权限标识。
 * 无 setter，通过工厂方法和行为方法变更状态。
 */
public class Menu {

    private Long    id;
    private Long    parentId;
    private String  menuType;
    private String  menuName;
    private String  menuKey;
    private String  path;
    private String  component;
    private String  icon;
    private Integer sortOrder;
    private Boolean isVisible;
    private Boolean isCache;
    private Boolean isExternal;
    private String  redirect;
    private String  permissionKey;
    private String  status;
    private String  remark;
    private Long    createdBy;

    private Menu() {}

    /** 从持久化状态重建（Repository 专用）。 */
    public static Menu reconstitute(Long id, Long parentId, String menuType,
                                    String menuName, String menuKey, String path,
                                    String component, String icon, Integer sortOrder,
                                    Boolean isVisible, Boolean isCache, Boolean isExternal,
                                    String redirect, String permissionKey,
                                    String status, String remark, Long createdBy) {
        Menu m = new Menu();
        m.id = id; m.parentId = parentId; m.menuType = menuType;
        m.menuName = menuName; m.menuKey = menuKey; m.path = path;
        m.component = component; m.icon = icon; m.sortOrder = sortOrder;
        m.isVisible = isVisible; m.isCache = isCache; m.isExternal = isExternal;
        m.redirect = redirect; m.permissionKey = permissionKey;
        m.status = status; m.remark = remark; m.createdBy = createdBy;
        return m;
    }

    /** 新建菜单（业务创建，ID 由持久化层回填）。 */
    public static Menu create(Long parentId, String menuType, String menuName,
                              String menuKey, String path, String component,
                              String icon, Integer sortOrder, Boolean isVisible,
                              Boolean isCache, Boolean isExternal, String redirect,
                              String permissionKey, String status, String remark) {
        if (menuName == null || menuName.isBlank()) throw new IllegalArgumentException("菜单名称不能为空");
        Menu m = new Menu();
        m.parentId = parentId != null ? parentId : 0L;
        m.menuType = menuType; m.menuName = menuName; m.menuKey = menuKey;
        m.path = path; m.component = component; m.icon = icon;
        m.sortOrder = sortOrder; m.isVisible = isVisible; m.isCache = isCache;
        m.isExternal = isExternal; m.redirect = redirect;
        m.permissionKey = permissionKey;
        m.status = status != null ? status : "active";
        m.remark = remark;
        return m;
    }

    /** 更新菜单字段（null 值跳过，不覆盖原值）。 */
    public void update(Long parentId, String menuType, String menuName, String menuKey,
                       String path, String component, String icon, Integer sortOrder,
                       Boolean isVisible, Boolean isCache, Boolean isExternal,
                       String redirect, String permissionKey, String status, String remark) {
        if (parentId != null)      this.parentId = parentId;
        if (menuType != null)      this.menuType = menuType;
        if (menuName != null && !menuName.isBlank()) this.menuName = menuName;
        if (menuKey != null)       this.menuKey = menuKey;
        if (path != null)          this.path = path;
        if (component != null)     this.component = component;
        if (icon != null)          this.icon = icon;
        if (sortOrder != null)     this.sortOrder = sortOrder;
        if (isVisible != null)     this.isVisible = isVisible;
        if (isCache != null)       this.isCache = isCache;
        if (isExternal != null)    this.isExternal = isExternal;
        if (redirect != null)      this.redirect = redirect;
        if (permissionKey != null) this.permissionKey = permissionKey;
        if (status != null)        this.status = status;
        if (remark != null)        this.remark = remark;
    }

    /** 仅 Repository 在 insert 后回填自增 ID 时使用。 */
    public void assignId(Long id) { this.id = id; }

    public Long    getId()           { return id; }
    public Long    getParentId()     { return parentId; }
    public String  getMenuType()     { return menuType; }
    public String  getMenuName()     { return menuName; }
    public String  getMenuKey()      { return menuKey; }
    public String  getPath()         { return path; }
    public String  getComponent()    { return component; }
    public String  getIcon()         { return icon; }
    public Integer getSortOrder()    { return sortOrder; }
    public Boolean getIsVisible()    { return isVisible; }
    public Boolean getIsCache()      { return isCache; }
    public Boolean getIsExternal()   { return isExternal; }
    public String  getRedirect()     { return redirect; }
    public String  getPermissionKey(){ return permissionKey; }
    public String  getStatus()       { return status; }
    public String  getRemark()       { return remark; }
    public Long    getCreatedBy()    { return createdBy; }
}
