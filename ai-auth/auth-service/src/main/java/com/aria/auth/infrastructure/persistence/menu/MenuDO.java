package com.aria.auth.infrastructure.persistence.menu;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 菜单/按钮数据对象。
 * menu_type：DIRECTORY=目录，MENU=菜单页面，BUTTON=按钮/接口权限
 */
@Getter
@Setter
@TableName("cs_auth.sys_menu")
public class MenuDO {
    /**
     * 主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    private Long parentId;
    private String menuType;       // DIRECTORY / MENU / BUTTON
    private String menuName;
    private String menuKey;        // 路由 name 或按钮 code，全局唯一
    private String path;           // 路由路径
    private String component;      // 前端组件路径
    private String icon;
    private Integer sortOrder;
    private Boolean isVisible;
    private Boolean isCache;
    private Boolean isExternal;
    private String redirect;
    private String permissionKey;  // BUTTON 类型关联的接口权限标识
    private String status;
    private String remark;
    private Long createdBy;
}
