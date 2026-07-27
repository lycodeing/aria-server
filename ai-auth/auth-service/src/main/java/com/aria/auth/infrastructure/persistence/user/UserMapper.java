package com.aria.auth.infrastructure.persistence.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper 接口。
 * 简单条件查询通过 Repository 层的 LambdaQueryWrapper 实现，
 * 此处仅保留无法用 Wrapper 表达的复杂查询。
 *
 * @author aria
 */
@Mapper
public interface UserMapper extends BaseMapper<UserDO> {

    /** 按用户名检查是否存在（未删除） */
    default boolean existsByUsername(String username) {
        return exists(Wrappers.<UserDO>lambdaQuery()
                .eq(UserDO::getUsername, username)
                .isNull(UserDO::getDeletedAt));
    }

    /** 按邮箱检查是否存在（未删除） */
    default boolean existsByEmail(String email) {
        return exists(Wrappers.<UserDO>lambdaQuery()
                .eq(UserDO::getEmail, email)
                .isNull(UserDO::getDeletedAt));
    }
}
