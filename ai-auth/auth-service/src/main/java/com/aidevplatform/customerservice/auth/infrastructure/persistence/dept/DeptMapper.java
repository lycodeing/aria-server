package com.aidevplatform.customerservice.auth.infrastructure.persistence.dept;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 部门 Mapper 接口。
 * 所有自定义 SQL 统一维护在 mapper/DeptMapper.xml，不使用 @Select/@Insert/@Delete 注解。
 */
@Mapper
public interface DeptMapper extends BaseMapper<DeptDO> {

    /**
     * 查询用户所属部门 ID 列表（通过 sys_user_dept 关联）。
     */
    List<Long> findDeptIdsByUserId(@Param("userId") Long userId);

    /**
     * 查询指定部门及其所有子部门的 ID 列表（递归 CTE）。
     * 用于 DEPT_TREE 数据权限范围过滤。
     */
    List<Long> findSubtreeDeptIds(@Param("deptId") Long deptId);

    /**
     * 查询角色自定义数据权限的部门 ID 列表。
     * 仅 scope_type=CUSTOM_DEPT 时生效。
     */
    List<Long> findCustomDeptIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 插入用户-部门关联记录。
     *
     * @param isPrimary 是否为主部门
     */
    int insertUserDept(@Param("userId") Long userId,
                       @Param("deptId") Long deptId,
                       @Param("isPrimary") boolean isPrimary);

    /**
     * 删除用户所有部门关联（重新分配部门前调用）。
     */
    int deleteUserDepts(@Param("userId") Long userId);
}
