package com.zhiqian.ops.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（沿用原项目设计），避免堆栈信息外汏。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequest(IllegalArgumentException e) {
        log.warn("bad request: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    /**
     * 不存在的路由 / 静态资源应返回 404，而不能被兵底的 Exception 处理器包装成 500。
     * 否则误打接口（如 /api/mcp/tools）会返回 500 并记录 internal error，
     * 干扰健康检查与告警。NoResourceFoundException 需 Spring Boot 3.2+；
     * NoHandlerFoundException 需开启 throw-exception-if-no-handler-found。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(Exception e) {
        log.warn("resource not found: {}", e.getMessage());
        return Result.error(404, "请求的资源或接口不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("internal error", e);
        return Result.error(500, "服务内部异常：" + e.getMessage());
    }
}
