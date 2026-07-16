package com.digixmed.icu.viform.config;

import com.digixmed.icu.viform.common.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：将未捕获的异常转为规范的 {@link ApiResult} 失败响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 415 Content-Type 不支持。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ApiResult handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("[API] 不支持的 Content-Type: {}", e.getContentType());
        return ApiResult.fail("不支持的Content-Type，请使用application/json", 415);
    }

    /**
     * 400 JSON 解析异常（如日期格式错误、字段类型不匹配）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResult handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[API] 请求体解析失败: {}", e.getMessage());
        return ApiResult.fail("请求体解析失败: " + e.getMostSpecificCause().getMessage(), 400);
    }

    /**
     * 其他未捕获异常 → 统一返回失败，不暴露堆栈（status=500）。
     */
    @ExceptionHandler(Exception.class)
    public ApiResult handleException(Exception e) {
        log.error("[API] 未预期异常", e);
        return ApiResult.fail("系统内部错误", 500);
    }
}
