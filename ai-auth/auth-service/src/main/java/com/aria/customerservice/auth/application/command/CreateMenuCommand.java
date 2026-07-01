package com.aria.auth.application.command;

import lombok.Data;

/**
 * 新建菜单命令对象（替代 Map&lt;String, Object&gt; 入参）。
 *
 * @author aria
 */
@Data
public class CreateMenuCommand {

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
}
