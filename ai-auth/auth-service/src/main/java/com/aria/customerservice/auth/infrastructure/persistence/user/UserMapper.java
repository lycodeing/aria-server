package com.aria.auth.infrastructure.persistence.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper 接口。
 * 简单条件查询通过 Repository 层的 LambdaQueryWrapper 实现，
 * 此处仅保留无法用 Wrapper 表达的复杂查询。
 *
 * @author aria
 */
@Mapper
public interface UserMapper extends BaseMapper<UserDO> {

    @Select("SELECT COUNT(*) > 0 FROM cs_auth.sys_user WHERE username = #{username} AND deleted_at IS NULL")
    boolean existsByUsername(@Param("username") String username);

    @Select("SELECT COUNT(*) > 0 FROM cs_auth.sys_user WHERE email = #{email} AND deleted_at IS NULL")
    boolean existsByEmail(@Param("email") String email);
}
