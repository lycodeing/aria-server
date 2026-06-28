package com.aidevplatform.common.core.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码定义（对齐 ai-delivery ErrorCode 枚举规范）。
 */
@Getter
@AllArgsConstructor
public enum CommonErrorCode {
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或 Token 已过期"),
    FORBIDDEN(40300, "权限不足"),
    NOT_FOUND(40400, "资源不存在"),
    RATE_LIMITED(42900, "请求过于频繁"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    BUSINESS_ERROR(600, "业务处理失败"),
    DUPLICATE_KEY(601, "数据重复"),
    CONFLICT(602, "数据冲突");

    private final int code;
    private final String message;

    public String getMsg() { return message; }
}
