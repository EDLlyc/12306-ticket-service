package com.ahu.ticket.common;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 * 面试亮点：避免 Tomcat 默认的错误页面或堆栈直接暴露给前端，保证接口健壮性
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        log.error("系统异常 traceId={}", traceId, e);
        return Result.error("服务器内部错误，traceId=" + traceId);
    }
}
