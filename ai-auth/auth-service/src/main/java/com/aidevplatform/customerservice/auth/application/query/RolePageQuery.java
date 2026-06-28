package com.aidevplatform.customerservice.auth.application.query;

import com.aidevplatform.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色分页查询条件对象。
 *
 * @author aidevplatform
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RolePageQuery extends PageQuery {

    /** 搜索关键词（匹配 roleKey 或 roleName，null 时查全部） */
    private String keyword;
}
