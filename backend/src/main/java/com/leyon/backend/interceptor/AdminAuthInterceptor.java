package com.leyon.backend.interceptor;

import com.leyon.backend.entity.User;
import com.leyon.backend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 管理员接口鉴权拦截器
 * 拦截 /api/admin/** 路径：校验当前用户角色为 admin，否则返回 403
 * 依赖 AuthInterceptor 已解析的 userId 属性
 *
 * @author leyon
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    /** 跨域预检请求方法 */
    private static final String OPTIONS_METHOD = "OPTIONS";

    /** 禁止访问状态码 */
    private static final int FORBIDDEN_CODE = 403;

    /** 响应内容类型 */
    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    /** 403 统一返回报文 */
    private static final String FORBIDDEN_RESPONSE = "{\"code\":403,\"message\":\"无管理员权限\",\"data\":null}";

    private final UserService userService;

    public AdminAuthInterceptor(UserService userService) {
        this.userService = userService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        if (OPTIONS_METHOD.equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String userId = (String) request.getAttribute("userId");
        if (!StringUtils.hasText(userId)) {
            return writeForbidden(response);
        }
        User user = userService.getById(userId);
        if (user == null || !User.ROLE_ADMIN.equals(user.getRole())) {
            return writeForbidden(response);
        }
        return true;
    }

    private boolean writeForbidden(@NonNull HttpServletResponse response) throws IOException {
        response.setStatus(FORBIDDEN_CODE);
        response.setContentType(JSON_CONTENT_TYPE);
        response.getWriter().write(FORBIDDEN_RESPONSE);
        return false;
    }
}