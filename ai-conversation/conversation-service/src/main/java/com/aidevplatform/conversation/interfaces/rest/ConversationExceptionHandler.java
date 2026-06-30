package com.aidevplatform.conversation.interfaces.rest;

import com.aidevplatform.common.web.response.R;
import com.aidevplatform.conversation.application.exception.SessionEnqueueException;
import com.aidevplatform.conversation.domain.SessionAlreadyAcceptedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 对话模块局部异常处理器。
 *
 * <p>处理 conversation-service 特有的业务异常，避免将模块依赖引入 common-web 的全局处理器。
 * Spring 优先匹配最具体的处理器，局部 @RestControllerAdvice 优先级高于全局兜底 Exception 处理。
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.aidevplatform.conversation.interfaces.rest")
public class ConversationExceptionHandler {

    /**
     * 会话入队失败（Redis 写入不可恢复错误）→ 503 Service Unavailable。
     */
    @ExceptionHandler(SessionEnqueueException.class)
    public ResponseEntity<R<Void>> handleSessionEnqueue(SessionEnqueueException e) {
        log.error("[Conversation] 会话入队失败 sessionId={}", e.getSessionId(), e);
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(R.fail(503, "会话入队失败，请稍后重试"));
    }

    /**
     * 会话已被其他座席抢占 → 409 Conflict。
     */
    @ExceptionHandler(SessionAlreadyAcceptedException.class)
    public ResponseEntity<R<Void>> handleSessionAlreadyAccepted(SessionAlreadyAcceptedException e) {
        log.warn("[Conversation] 会话已被抢占 sessionId={}", e.getSessionId());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(R.fail(409, e.getMessage()));
    }
}
