package com.aidevplatform.customerservice.auth.application.query;

import com.aidevplatform.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询条件对象。
 *
 * @author aidevplatform
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserPageQuery extends PageQuery {

    /** 搜索关键词（匹配用户名/显示名/邮箱，null 时查全部） */
    private String keyword;
}
