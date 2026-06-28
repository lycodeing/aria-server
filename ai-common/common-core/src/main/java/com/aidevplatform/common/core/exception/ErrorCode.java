package com.aidevplatform.common.core.exception;

/**
 * 错误码接口。
 * <p>各服务通过 enum 实现此接口，定义自己的错误码。
 */
public interface ErrorCode {
    String getCode();
    String getMessage();
}
