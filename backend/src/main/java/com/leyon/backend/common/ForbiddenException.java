package com.leyon.backend.common;

/**
 * 权限不足异常（P2-10 多租户角色矩阵）
 * 由 GlobalExceptionHandler 统一转为 403 响应（code=403），用于组织数据越权访问拦截
 *
 * @author leyon
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}