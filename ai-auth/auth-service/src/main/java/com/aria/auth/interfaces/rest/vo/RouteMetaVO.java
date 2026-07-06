package com.aria.auth.interfaces.rest.vo;

import lombok.Data;

/**
 * Vben 动态路由 meta 字段格式。
 * 与前端 RouteRecordStringComponent.meta 对齐。
 */
@Data
public class RouteMetaVO {
    /**
     * 菜单显示名称（国际化 key 或直接中文）
     */
    private String title;
    /**
     * 图标名称，如 lucide:message-circle
     */
    private String icon;
    /**
     * 是否开启 keepAlive 缓存
     */
    private Boolean keepAlive;
    /**
     * 是否隐藏（不在菜单显示但路由可访问）
     */
    private Boolean hideInMenu;
    /**
     * 是否外链（新窗口打开）
     */
    private Boolean link;
    /**
     * 排序权重
     */
    private Integer order;
    /**
     * 当前路由激活时高亮的菜单路径
     */
    private String activeMenu;
}
