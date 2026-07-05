package com.aria.auth.interfaces.rest.vo;

import lombok.Data;

import java.util.List;

/**
 * 菜单 VO（系统管理页使用的平铺格式）
 */
@Data
public class MenuVO {
    private Long id;
    private Long parentId;
    private String menuType;
    private String menuName;
    private String menuKey;
    private String path;
    private String component;
    private String icon;
    private Integer sortOrder;
    private Boolean isVisible;
    private Boolean isCache;
    private Boolean isExternal;
    private String redirect;
    private String permissionKey;
    private String status;
    private List<MenuVO> children;
}
