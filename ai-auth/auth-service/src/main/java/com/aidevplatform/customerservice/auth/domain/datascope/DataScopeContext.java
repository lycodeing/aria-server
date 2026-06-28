package com.aidevplatform.customerservice.auth.domain.datascope;

import java.util.List;

/**
 * 数据权限上下文（ThreadLocal 持有）。
 * AOP 切面在方法执行前设置，方法结束后清理，避免内存泄漏。
 *
 * <p>三种状态语义：
 * <ul>
 *   <li>{@code null}    — 未启用数据权限过滤，查询不加 dept_id 限制</li>
 *   <li>非空列表       — 查询结果限定在这些部门 ID 范围内</li>
 *   <li>空列表 []      — 该用户无任何部门，返回空结果（禁止查询）</li>
 * </ul>
 */
public final class DataScopeContext {

    private DataScopeContext() {}

    private static final ThreadLocal<List<Long>> DEPT_IDS = new ThreadLocal<>();

    /** AOP 切面调用：设置当前请求的可见部门 ID 列表 */
    public static void setDeptIds(List<Long> deptIds) {
        DEPT_IDS.set(deptIds);
    }

    /**
     * Service 方法内调用：获取当前用户可见的部门 ID 列表。
     * 返回 null 表示无需过滤（全量数据权限）。
     */
    public static List<Long> getDeptIds() {
        return DEPT_IDS.get();
    }

    /** AOP 切面调用：请求结束后清理，防止 ThreadLocal 内存泄漏 */
    public static void clear() {
        DEPT_IDS.remove();
    }

    /** 判断当前是否需要进行数据范围过滤 */
    public static boolean isFiltered() {
        return DEPT_IDS.get() != null;
    }
}
