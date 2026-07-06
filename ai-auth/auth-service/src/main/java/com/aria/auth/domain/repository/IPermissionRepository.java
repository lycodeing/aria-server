package com.aria.auth.domain.repository;

import com.aria.auth.domain.model.role.Permission;

import java.util.List;

/**
 * 接口权限仓储接口（Domain 层定义，Infrastructure 层实现）。
 */
public interface IPermissionRepository {

    /**
     * 查询所有权限（用于权限树展示）
     */
    List<Permission> findAll();

    /**
     * 查询用户拥有的接口权限列表
     */
    List<Permission> findByUserId(Long userId);
}
