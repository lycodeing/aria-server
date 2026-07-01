package com.aria.customerservice.auth.application.result;

import java.util.List;

/**
 * 菜单管理后台树节点 VO（替代 Map&lt;String, Object&gt;）。
 *
 * @author aria
 */
public class MenuAdminVO {

    private final Long    id;
    private final Long    parentId;
    private final String  menuType;
    private final String  menuName;
    private final String  menuKey;
    private final String  path;
    private final String  component;
    private final String  icon;
    private final Integer sortOrder;
    private final Boolean isVisible;
    private final Boolean isCache;
    private final Boolean isExternal;
    private final String  redirect;
    private final String  permissionKey;
    private final String  status;
    private final String  remark;
    private final List<MenuAdminVO> children;

    public MenuAdminVO(Long id, Long parentId, String menuType, String menuName,
                       String menuKey, String path, String component, String icon,
                       Integer sortOrder, Boolean isVisible, Boolean isCache,
                       Boolean isExternal, String redirect, String permissionKey,
                       String status, String remark, List<MenuAdminVO> children) {
        this.id = id; this.parentId = parentId; this.menuType = menuType;
        this.menuName = menuName; this.menuKey = menuKey; this.path = path;
        this.component = component; this.icon = icon; this.sortOrder = sortOrder;
        this.isVisible = isVisible; this.isCache = isCache; this.isExternal = isExternal;
        this.redirect = redirect; this.permissionKey = permissionKey;
        this.status = status; this.remark = remark; this.children = children;
    }

    public Long   getId()           { return id; }
    public Long   getParentId()     { return parentId; }
    public String getMenuType()     { return menuType; }
    public String getMenuName()     { return menuName; }
    public String getMenuKey()      { return menuKey; }
    public String getPath()         { return path; }
    public String getComponent()    { return component; }
    public String getIcon()         { return icon; }
    public Integer getSortOrder()   { return sortOrder; }
    public Boolean getIsVisible()   { return isVisible; }
    public Boolean getIsCache()     { return isCache; }
    public Boolean getIsExternal()  { return isExternal; }
    public String getRedirect()     { return redirect; }
    public String getPermissionKey(){ return permissionKey; }
    public String getStatus()       { return status; }
    public String getRemark()       { return remark; }
    public List<MenuAdminVO> getChildren() { return children; }
}
