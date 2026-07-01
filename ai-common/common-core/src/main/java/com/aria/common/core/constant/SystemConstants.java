package com.aria.common.core.constant;

/**
 * 系统级公共常量。
 * <p>跨服务共享的固定 ID 和系统标识，避免在各服务中散落重复定义。
 */
public final class SystemConstants {

    private SystemConstants() {}

    /** 系统操作人 ID（0 表示系统自动触发，非真实用户） */
    public static final long SYSTEM_OPERATOR_ID = 0L;

    /** 默认组织 ID（单租户场景下固定为 1） */
    public static final long DEFAULT_ORG_ID = 1L;

    /** 默认工作空间 ID（单工作空间场景下固定为 0） */
    public static final long DEFAULT_WORKSPACE_ID = 0L;
}
