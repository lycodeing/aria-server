package com.aria.auth.domain.repository;

import com.aria.auth.application.query.UserPageQuery;
import com.aria.auth.domain.model.user.User;
import com.aria.auth.domain.model.user.UserId;
import com.aria.common.core.page.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 用户仓储接口（Domain 层定义，Infrastructure 层实现）。
 */
public interface IUserRepository {

    User save(User user);

    Optional<User> findById(UserId id);

    /**
     * 批量查询用户（一条 IN 查询），用于消除逐个 findById 的 N+1。
     *
     * @param ids 用户 ID 集合，空集合返回空列表
     * @return 匹配到的用户列表（不保证顺序，找不到的 ID 不在结果中）
     */
    List<User> findByIds(Collection<UserId> ids);

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
