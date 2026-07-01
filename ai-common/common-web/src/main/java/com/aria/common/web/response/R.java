package com.aria.common.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

/**
 * 统一 API 响应包装类。
 * <p>对齐 ai-delivery 规范：code=200 成功，字段名 msg（非 message）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record R<T>(int code, String msg, T data,
                   @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {

    public static final String MDC_TRACE_ID = "traceId";
    private static final int SUCCESS_CODE = 200;
    private static final String SUCCESS_MSG = "success";

    public static <T> R<T> ok() { return new R<>(SUCCESS_CODE, SUCCESS_MSG, null, currentTraceId()); }
    public static <T> R<T> ok(T data) { return new R<>(SUCCESS_CODE, SUCCESS_MSG, data, currentTraceId()); }
    public static <T> R<T> fail(int code, String msg) { return new R<>(code, msg, null, currentTraceId()); }
    public static <T> R<T> fail(String code, String msg) {
        try { return new R<>(Integer.parseInt(code), msg, null, currentTraceId()); }
        catch (NumberFormatException e) { return new R<>(code.hashCode(), msg, null, currentTraceId()); }
    }
    public boolean isSuccess() { return code == SUCCESS_CODE; }
    private static String currentTraceId() { return MDC.get(MDC_TRACE_ID); }
}
