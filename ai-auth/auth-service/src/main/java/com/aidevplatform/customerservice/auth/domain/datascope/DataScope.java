package com.aidevplatform.customerservice.auth.domain.datascope;

import java.lang.annotation.*;

/**
 * 数据权限注解，标注在 Service 方法上。
 * 框架会在方法执行前，将当前用户可见的部门 ID 列表注入 DataScopeContext，
 * Service 方法内通过 DataScopeContext.getDeptIds() 获取并拼入查询条件。
 *
 * <p>使用示例：
 * <pre>
 * {@literal @}DataScope(field = "dept_id")
 * public List<ConversationSession> listSessions(SessionQuery query) {
 *     List<Long> deptIds = DataScopeContext.getDeptIds();
 *     if (deptIds != null) {
 *         query.setDeptIds(deptIds);  // 查询时 WHERE dept_id IN (...)
 *     }
 *     return sessionRepository.list(query);
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataScope {

    /**
     * 数据权限过滤的字段名（对应表中的部门 ID 字段，如 dept_id / creator_dept_id）。
     * 仅作文档说明，实际过滤逻辑由调用方根据 DataScopeContext.getDeptIds() 实现。
     */
    String field() default "dept_id";

    /**
     * 是否允许超级管理员绕过数据权限（ALL 范围自动放行）。
     * 默认 true，特殊场景（如安全审计）可设为 false 强制过滤。
     */
    boolean allowAll() default true;
}
