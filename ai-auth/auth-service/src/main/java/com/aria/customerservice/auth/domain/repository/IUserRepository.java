package com.aria.customerservice.auth.domain.repository;

import com.aria.customerservice.auth.application.query.UserPageQuery;
import com.aria.customerservice.auth.domain.model.user.User;
import com.aria.customerservice.auth.domain.model.user.UserId;
import com.aria.common.core.page.PageResult;

import java.util.List;
import java.util.Optional;

/**
 * 用户仓储接口（Domain 层定义，Infrastructure 层实现）。
 */
public interface IUserRepository {

    User save(User user);
    Optional<User> findById(UserId id);
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    List<String> findRoleKeysByUserId(Long userId);
    List<String> findPermissionKeysByUserId(Long userId);

    /**
     * 分页搜索用户列表。
     *
     * @param query 分页查询条件
     * @return 分页结果
     */
    PageResult<User> search(UserPageQuery query);

    void delete(UserId id);
}
