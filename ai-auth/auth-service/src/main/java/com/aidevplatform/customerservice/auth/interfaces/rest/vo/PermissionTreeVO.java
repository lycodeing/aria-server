package com.aidevplatform.customerservice.auth.interfaces.rest.vo;

import java.util.List;

/**
 * 权限树模块节点 VO。
 */
public record PermissionTreeVO(String module, List<PermissionItemVO> permissions) {}
