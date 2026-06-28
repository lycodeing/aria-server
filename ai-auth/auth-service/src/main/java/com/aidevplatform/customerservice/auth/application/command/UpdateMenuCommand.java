package com.aidevplatform.customerservice.auth.application.command;

import lombok.Data;

/**
 * 更新菜单命令对象（替代 Map&lt;String, Object&gt; 入参）。
 * 所有字段均可为 null，null 表示不修改对应字段。
 *
 * @author aidevplatform
 */
@Data
public class UpdateMenuCommand {

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
