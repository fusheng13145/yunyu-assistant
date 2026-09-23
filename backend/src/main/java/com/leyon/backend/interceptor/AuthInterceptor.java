package com.leyon.backend.interceptor;

import com.leyon.backend.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * JWT 认证拦截器
 * 校验请求头中的 Token，完成接口鉴权，放行跨域 OPTIONS 预检请求
 * 
 * @author leyon
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * Token 请求头名称
     */
    private static final String AUTHORIZATION_HEADER = "Authorization";
    /**
     * Bearer 前缀标识
     */
    private static final String BEARER_PREFIX = "Bearer ";
    /**
     * Bearer 前缀长度
     */
    private static final int BEARER_PREFIX_LENGTH = 7;
    /**
     * 跨域预检请求方法
     */
    private static final String OPTIONS_METHOD = "OPTIONS";
    /**
     * 未授权状态码
     */
    private static final int UNAUTHORIZED_CODE = 401;
    /**
     * 响应内容类型
     */
    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";
    /**
     * 未授权统一返回报文
     */
    private static final String UNAUTHORIZED_RESPONSE = "{\"code\":401,\"message\":\"Unauthorized\",\"data\":null}";

    private final JwtUtil jwtUtil;

    /**
     * 构造器注入JWT工具类
     * @param jwtUtil JWT工具实例
     */
    public AuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * 前置拦截处理：请求进入控制器前执行鉴权逻辑
     * 
     * @param request 请求对象
     * @param response 响应对象
     * @param handler 处理器
     * @return true-放行请求，false-拦截请求
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws IOException {
        // 放行跨域 OPTIONS 预检请求
        if (OPTIONS_METHOD.equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 获取请求头中的Token
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            // 截取真实Token
            String token = authHeader.substring(BEARER_PREFIX_LENGTH);
            // 校验Token有效性（含类型：只有 access 令牌可当会话凭据，见 JwtUtil#validateAccessToken）
            if (jwtUtil.validateAccessToken(token)) {
                // 解析用户ID存入请求域，供后续业务使用
                String userId = jwtUtil.getUserIdFromToken(token);
                request.setAttribute("userId", userId);
                return true;
            }
        }

        // Token不存在/无效，返回401未授权
        handleUnauthorizedResponse(response);
        return false;
    }

    /**
     * 封装401未授权响应
     * 
     * @param response 响应对象
     * @throws IOException 流写入异常
     */
    private void handleUnauthorizedResponse(@NonNull HttpServletResponse response) throws IOException {
        response.setStatus(UNAUTHORIZED_CODE);
        response.setContentType(JSON_CONTENT_TYPE);
        response.getWriter().write(UNAUTHORIZED_RESPONSE);
    }
}