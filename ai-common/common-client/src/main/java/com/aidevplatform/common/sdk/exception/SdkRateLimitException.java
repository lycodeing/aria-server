package com.aidevplatform.common.sdk.exception;

/**
 * SDK 限流异常（HTTP 429）。
 */
public class SdkRateLimitException extends SdkException {

    public SdkRateLimitException(String message) {
        super(429, message);
    }
}
