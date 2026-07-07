package com.aria.sdk.auth.exception;

import com.aria.common.sdk.exception.SdkException;

/**
 * auth-client 业务异常。
 *
 * <p>封装两种失败：
 * <ul>
 *   <li>HTTP 层失败（非 2xx）：{@link #getHttpStatus()} 携带原始 HTTP 状态码</li>
 *   <li>业务层失败（HTTP 2xx 但 {@code R.code != 200}）：{@link #getBizCode()} 携带业务码</li>
 * </ul>
 *
 * <p>上层可基于这两个字段做细粒度熔断，例如 401/403 触发密钥重取，
 * 404 触发降级配置，5xx 触发本地缓存兜底。
 *
 * @author lycodeing
 * @since 2026-07
 */
public class AuthClientException extends SdkException {

    private static final long serialVersionUID = 1L;

    /** 未知状态码占位（既非 HTTP 状态也非业务码时使用）。 */
    public static final int UNKNOWN_CODE = -1;

    /** 原始 HTTP 状态码；HTTP 层未失败时为 {@link #UNKNOWN_CODE}。 */
    private final int httpStatus;

    /** 业务响应码（{@code R.code}）；未产生业务响应时为 {@link #UNKNOWN_CODE}。 */
    private final int bizCode;

    public AuthClientException(String message) {
        this(message, UNKNOWN_CODE, UNKNOWN_CODE, null);
    }

    public AuthClientException(String message, Throwable cause) {
        this(message, UNKNOWN_CODE, UNKNOWN_CODE, cause);
    }

    public AuthClientException(String message, int httpStatus, int bizCode, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.bizCode = bizCode;
    }

    /** @return 原始 HTTP 状态码，未记录时为 {@link #UNKNOWN_CODE}。 */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** @return 服务端 {@code R.code} 业务码，未记录时为 {@link #UNKNOWN_CODE}。 */
    public int getBizCode() {
        return bizCode;
    }
}
