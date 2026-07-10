package com.aria.common.web.exception;

import com.aria.common.core.exception.BusinessException;
import com.aria.common.web.response.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常: code={}, message={}, path={}", e.getCode(), e.getMessage(), request.getRequestURI());
        // 业务错误码与 HTTP 状态码分离：
        // - 标准三位 HTTP 错误码（400~599）直接作为 HTTP 状态码
        // - 四位以上业务码（如 4004/5010）统一返回 HTTP 400，客户端通过 R.code 获取业务含义
        // 避免将 4004 直接设为 HTTP 4004（无效状态码）导致客户端行为不可预期
        int httpStatus = isStandardHttpError(e.getCode()) ? e.getCode() : 400;
        return ResponseEntity.status(httpStatus).body(R.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 判断是否为标准三位 HTTP 错误状态码（400~599 范围内）。
     */
    private static boolean isStandardHttpError(int code) {
        return code >= 400 && code <= 599 && code < 1000;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, "参数校验失败: " + detail));
    }

    /**
     * @ModelAttribute 对象绑定失败（如枚举值非法、数字格式错误），返回 400。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBindException(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("请求参数绑定失败: {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, "请求参数错误: " + detail));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream().map(ConstraintViolation::getMessage).collect(Collectors.joining("; "));
        log.warn("约束违反: {}", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, "请求体缺失或格式错误"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String msg = e.getRequiredType() != null ? String.format("参数 '%s' 类型错误", e.getName()) : String.format("参数 '%s' 格式错误", e.getName());
        log.warn("参数类型错误: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, msg));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必需参数: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, "缺少必需参数: " + e.getParameterName()));
    }

    @ExceptionHandler(cn.dev33.satoken.exception.NotLoginException.class)
    public ResponseEntity<R<Void>> handleNotLogin(cn.dev33.satoken.exception.NotLoginException e) {
        log.warn("未登录: type={}", e.getType());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(401, "未登录或 Token 已过期"));
    }

    @ExceptionHandler(cn.dev33.satoken.exception.NotPermissionException.class)
    public ResponseEntity<R<Void>> handleNotPermission(cn.dev33.satoken.exception.NotPermissionException e) {
        log.warn("无权限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "无权限执行此操作"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(NoHandlerFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "接口不存在: " + e.getRequestURL()));
    }

    /**
     * Spring 6 静态资源路由未找到（包括普通接口路径不存在的情况），返回 404。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "接口不存在: " + e.getResourcePath()));
    }

    /**
     * HTTP 方法不被支持（如对只有 GET 的接口发送 DELETE/PUT），返回 405。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("不支持的请求方法: method={}, message={}", e.getMethod(), e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(R.fail(405, "不支持的请求方法: " + e.getMethod()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("未预期的异常: path={}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail(500, "服务器内部错误"));
    }
}
