package com.aidevplatform.common.sdk.exception;

/**
 * SDK 基础异常。
 */
public class SdkException extends RuntimeException {

    private final int statusCode;

    public SdkException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public SdkException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public SdkException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * 从 HTTP 响应构造异常。
     */
    public static SdkException fromResponse(int statusCode, String body) {
        return new SdkException(statusCode, "HTTP " + statusCode + ": " + body);
    }
}
