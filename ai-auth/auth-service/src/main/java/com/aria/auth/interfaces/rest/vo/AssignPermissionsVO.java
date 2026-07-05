package com.aria.auth.interfaces.rest.vo;

import java.util.List;

/**
 * 权限分配结果 VO。
 */
public record AssignPermissionsVO(Long roleId, List<Long> permissionIds, String message) {
}
