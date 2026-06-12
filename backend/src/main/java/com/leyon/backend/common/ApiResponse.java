package com.leyon.backend.common;

/**
 * 统一接口返回结果封装
 * @param <T> 响应数据泛型
 * @author leyon
 */
public class ApiResponse<T> {

    /** 响应码 */
    private int code;
    /** 响应提示信息 */
    private String message;
    /** 响应业务数据 */
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ===================== 成功响应 =====================
    /**
     * 成功响应（无数据）
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "操作成功", null);
    }

    /**
     * 成功响应（携带数据）
     * @param data 业务数据
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "操作成功", data);
    }

    /**
     * 成功响应（自定义提示语 + 数据）
     * @param msg 提示信息
     * @param data 业务数据
     */
    public static <T> ApiResponse<T> success(String msg, T data) {
        return new ApiResponse<>(200, msg, data);
    }

    // ===================== 失败响应 =====================
    /**
     * 系统异常（500）
     * @param msg 错误描述
     */
    public static <T> ApiResponse<T> error(String msg) {
        return new ApiResponse<>(500, msg, null);
    }

    /**
     * 系统异常（500，携带额外数据）
     * @param msg 错误描述
     * @param data 附加数据
     */
    public static <T> ApiResponse<T> error(String msg, T data) {
        return new ApiResponse<>(500, msg, data);
    }

    /**
     * 参数错误（400）
     * @param msg 错误描述
     */
    public static <T> ApiResponse<T> paramError(String msg) {
        return new ApiResponse<>(400, msg, null);
    }

    /**
     * 参数错误（400，携带额外数据）
     * @param msg 错误描述
     * @param data 附加数据
     */
    public static <T> ApiResponse<T> paramError(String msg, T data) {
        return new ApiResponse<>(400, msg, data);
    }

    /**
     * 自定义响应码 + 提示语
     * @param code 响应码
     * @param msg 提示信息
     */
    public static <T> ApiResponse<T> result(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }

    /**
     * 全参数自定义响应
     * @param code 响应码
     * @param msg 提示信息
     * @param data 业务数据
     */
    public static <T> ApiResponse<T> result(int code, String msg, T data) {
        return new ApiResponse<>(code, msg, data);
    }

    // ===================== Getter & Setter =====================
    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}