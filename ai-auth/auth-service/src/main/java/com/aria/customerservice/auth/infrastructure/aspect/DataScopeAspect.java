package com.aria.auth.infrastructure.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.aria.auth.domain.datascope.DataScope;
import com.aria.auth.domain.datascope.DataScopeContext;
import com.aria.auth.infrastructure.persistence.dept.DeptMapper;
import com.aria.auth.infrastructure.persistence.role.RoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据权限 AOP 切面（基础设施层）。
 * 拦截标注了 {@link DataScope} 的 Service 方法，在执行前根据当前用户的角色数据范围
 * 计算可见部门 ID 集合，注入 {@link DataScopeContext}；方法结束后清理上下文。
 *
 * <p>数据权限计算规则（取所有角色的并集，宽松优先）：
 * <ol>
 *   <li>任意角色的 scope_type = ALL      → 返回 null（全量，不过滤）</li>
 *   <li>scope_type = DEPT_TREE           → 当前用户的主部门及所有子部门</li>
 *   <li>scope_type = DEPT_ONLY           → 仅当前用户的主部门</li>
 *   <li>scope_type = CUSTOM_DEPT         → 角色配置的自定义部门列表</li>
 *   <li>scope_type = SELF / 无配置       → 空列表（调用方用 creator_id 过滤）</li>
 * </ol>
 *
 * <p>注意：切面属于基础设施横切关注点，放于 infrastructure/aspect 包，
 * 通过 domain/datascope 的 {@link DataScope} 注解和 {@link DataScopeContext} 与领域层解耦。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DataScopeAspect {

    private final RoleMapper roleMapper;
    private final DeptMapper deptMapper;

    @Around("@annotation(com.aria.auth.domain.datascope.DataScope)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig  = (MethodSignature) pjp.getSignature();
        DataScope       anno = sig.getMethod().getAnnotation(DataScope.class);

        try {
            List<Long> deptIds = resolveDeptIds(anno);
            DataScopeContext.setDeptIds(deptIds);
            log.debug("数据权限注入，method={}，deptIds={}", sig.getName(),
                    deptIds == null ? "ALL" : deptIds);
            return pjp.proceed();
        } finally {
            DataScopeContext.clear();
        }
    }

    /**
     * 计算当前用户可见的部门 ID 集合。
     * 返回 null 表示全量（不过滤）；返回空列表表示无权查看任何数据。
     */
    private List<Long> resolveDeptIds(DataScope anno) {
        if (!StpUtil.isLogin()) {
            return List.of();
        }

        Long userId = StpUtil.getLoginIdAsLong();

        List<Long> roleIds = roleMapper.findRolesByUserId(userId)
                .stream()
                .map(r -> r.getId())
                .toList();

        if (roleIds.isEmpty()) {
            return List.of();
        }

        Set<Long> deptIdSet = new HashSet<>();

        for (Long roleId : roleIds) {
            String scopeType = resolveScopeType(roleId);

            switch (scopeType) {
                case "ALL" -> {
                    if (anno.allowAll()) {
                        // 任意角色拥有全量权限，直接返回 null（不过滤）
                        return null;
                    }
                }
                case "DEPT_TREE" -> {
                    // 本部门及所有子部门
                    List<Long> userDeptIds = deptMapper.findDeptIdsByUserId(userId);
                    for (Long deptId : userDeptIds) {
                        deptIdSet.addAll(deptMapper.findSubtreeDeptIds(deptId));
                    }
                }
                case "DEPT_ONLY" -> {
                    // 仅本部门
                    deptIdSet.addAll(deptMapper.findDeptIdsByUserId(userId));
                }
                case "CUSTOM_DEPT" -> {
                    // 自定义部门列表
                    deptIdSet.addAll(deptMapper.findCustomDeptIdsByRoleId(roleId));
                }
                default -> {
                    // SELF 或未配置：不添加部门，由调用方用 creator_id 过滤
                }
            }
        }

        return new ArrayList<>(deptIdSet);
    }

    /**
     * 查询角色数据权限范围类型，无配置或查询异常时降级为 SELF。
     */
    private String resolveScopeType(Long roleId) {
        try {
            String scopeType = roleMapper.findScopeTypeByRoleId(roleId);
            return scopeType != null ? scopeType : "SELF";
        } catch (Exception e) {
            log.warn("查询角色数据权限范围失败，roleId={}，降级为 SELF", roleId, e);
            return "SELF";
        }
    }
}
