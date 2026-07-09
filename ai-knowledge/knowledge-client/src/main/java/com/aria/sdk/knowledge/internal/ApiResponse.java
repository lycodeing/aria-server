package com.aria.sdk.knowledge.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * knowledge-service 响应体的内部映射（与服务端 {@code R<T>} 字段兼容）。
 *
 * <p>刻意不直接依赖 {@code common-web} 的 {@code R} 记录类型，避免 SDK 模块
 * 对 Web 层横切组件产生编译期反向依赖。字段名保持与服务端一致：
 * {@code code / msg / data / traceId}。
 *
 * @author lycodeing
 * @since 2026-07
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiResponse<T>(Integer code, String msg, T data, String traceId) {

    /**
     * 业务成功码，与服务端 {@code R<T>} 约定保持一致。
     */
    public static final int SUCCESS_CODE = 200;

    public boolean isSuccess() {
        return code != null && code == SUCCESS_CODE;
    }
}