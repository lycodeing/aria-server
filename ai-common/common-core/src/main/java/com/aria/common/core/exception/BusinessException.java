package com.aria.common.core.exception;

import lombok.Getter;

/**
 * 业务异常（对齐 ai-delivery 规范：int code + message）。
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final String message;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(CommonErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    public BusinessException(CommonErrorCode errorCode, String detail) {
        super(detail);
        this.code = errorCode.getCode();
        this.message = detail;
    }

    public static BusinessException of(CommonErrorCode errorCode) {
        return new BusinessException(errorCode);
    }

    public static BusinessException of(CommonErrorCode errorCode, String detail) {
        return new BusinessException(errorCode, detail);
    }

    public static BusinessException of(int code, String message) {
        return new BusinessException(code, message);
    }

    /** Legacy compat: String code → int via parse/hashCode */
    public static BusinessException of(String code, String message) {
        try { return new BusinessException(Integer.parseInt(code), message); }
        catch (NumberFormatException e) { return new BusinessException(code.hashCode(), message); }
    }

    /** Get code as String for legacy callers */
    public String getCodeAsString() { return String.valueOf(code); }
}
