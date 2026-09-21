package com.leyon.backend.common;

/**
 * 用量配额超限异常（P2-10 用量配额与账单统计）
 * 由 GlobalExceptionHandler 统一转为 403 响应（code=403），用于配额超限拦截
 *
 * @author leyon
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}