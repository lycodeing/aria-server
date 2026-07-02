package com.aria.auth.infrastructure.persistence.role;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface PermissionMapper extends BaseMapper<PermissionDO> {
    @Select("SELECT p.* FROM cs_auth.sys_permission p INNER JOIN auth.sys_role_permission rp ON p.id = rp.permission_id INNER JOIN auth.sys_user_role ur ON rp.role_id = ur.role_id WHERE ur.user_id = #{userId}")
    List<PermissionDO> findPermissionsByUserId(@Param("userId") Long userId);
}
