package com.leyon.backend.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.common.QuotaExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 统一捕获并处理控制器抛出的各类异常，规范接口异常返回格式
 *
 * @author leyon
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    /** 默认业务异常提示文案 */
    private static final String DEFAULT_BUSINESS_ERROR_MSG = "请求处理失败";
    /** 参数校验统一提示文案 */
    private static final String VALIDATE_FAIL_MSG = "参数校验失败";
    /** 系统内部错误提示文案 */
    private static final String SERVER_ERROR_MSG = "服务器内部错误，请稍后重试";

    /**
     * 处理用量配额超限异常（P2-10 配额拦截）
     */
    @ExceptionHandler(QuotaExceededException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleQuotaExceeded(QuotaExceededException e) {
        log.warn("用量配额超限：{}", e.getMessage());
        String safeMessage = sanitizeErrorMessage(e.getMessage());
        return ApiResponse.result(403, safeMessage);
    }

    /**
     * 处理权限不足异常（P2-10 组织角色矩阵越权拦截）
     */
    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleForbidden(ForbiddenException e) {
        log.warn("权限不足：{}", e.getMessage());
        String safeMessage = sanitizeErrorMessage(e.getMessage());
        return ApiResponse.result(403, safeMessage);
    }

    /**
     * 处理通用业务运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleRuntimeException(@NonNull RuntimeException e) {
        log.warn("业务异常：{}", e.getMessage());
        String message = e.getMessage();
        // 空文案兜底
        if (message == null || message.isBlank()) {
            message = DEFAULT_BUSINESS_ERROR_MSG;
        }
        // 安全改进：对可能包含内部信息的异常消息进行脱敏
        String safeMessage = sanitizeErrorMessage(message);
        return ApiResponse.paramError(safeMessage);
    }

    /**
     * 异常消息脱敏，过滤可能泄露内部实现细节的信息
     */
    private String sanitizeErrorMessage(String rawMessage) {
        if (rawMessage == null) return DEFAULT_BUSINESS_ERROR_MSG;
        // 过滤常见敏感关键词
        String lower = rawMessage.toLowerCase();
        if (lower.contains("sql") || lower.contains("database") || lower.contains("jdbc")
                || lower.contains("password") || lower.contains("secret") || lower.contains("credential")
                || lower.contains("stacktrace") || lower.contains("classpath")
                || lower.contains("internal") || lower.contains("path")) {
            return DEFAULT_BUSINESS_ERROR_MSG;
        }
        // 限制返回消息长度，防止过长信息泄露
        if (rawMessage.length() > 200) {
            return DEFAULT_BUSINESS_ERROR_MSG;
        }
        return rawMessage;
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误：{}", e.getMessage());
        // 与 RuntimeException 一致地执行脱敏，避免泄露内部实现细节
        String safeMessage = sanitizeErrorMessage(e.getMessage());
        return ApiResponse.paramError(safeMessage);
    }

    /**
     * 处理请求参数校验异常（@Valid 校验失败）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, String>> handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> errorMap = new HashMap<>();
        // 遍历所有校验错误，封装字段与错误信息
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errorMap.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        log.warn("参数校验失败：{}", errorMap);
        return ApiResponse.paramError(VALIDATE_FAIL_MSG, errorMap);
    }

    /**
     * 兜底处理所有未捕获的未知异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGenericException(Exception e) {
        log.error("服务器内部异常", e);
        return ApiResponse.error(SERVER_ERROR_MSG);
    }
}