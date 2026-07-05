package com.aria.auth.application.service;

import java.util.List;

/**
 * 登录事务内结果快照，由 {@link LoginTransactionService} 返回，
 * 供 {@link AuthApplicationService} 事务外建立 Sa-Token 会话使用。
 * 不含任何框架引用，纯数据传递。
 */
record LoginContext(
        Long userId,
        String username,
        String displayName,
        List<String> roleKeys,
        List<String> permissionKeys,
        long timeout,
        boolean mustChangePassword) {
}
