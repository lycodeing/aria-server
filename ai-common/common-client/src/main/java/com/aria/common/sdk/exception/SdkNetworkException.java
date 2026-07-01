package com.aria.common.sdk.exception;

/**
 * SDK 网络异常（IOException 包装）。
 */
public class SdkNetworkException extends SdkException {

    public SdkNetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
