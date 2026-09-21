package com.leyon.backend.interceptor;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * OpenAPI 鉴权拦截器（P2-10 开放 OpenAPI）
 * 拦截 /api/open/** 请求，通过 X-API-Key 请求头校验第三方应用（与 AuthInterceptor 完全隔离）
 * 校验通过后注入 appId/userId/orgId 到 request attributes，供后续业务按属主身份执行与配额统计
 *
 * @author leyon
 */
@Component
public class OpenApiAuthInterceptor implements HandlerInterceptor {

    /** API Key 请求头名称 */
    private static final String API_KEY_HEADER = "X-API-Key";
    /** 未授权状态码 */
    private static final int UNAUTHORIZED_CODE = 401;
    /** 响应内容类型 */
    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";
    /** 未授权统一返回报文 */
    private static final String UNAUTHORIZED_RESPONSE = "{\"code\":401,\"message\":\"API Key 无效或已停用\",\"data\":null}";

    /** request attribute：第三方应用ID */
    public static final String ATTR_APP_ID = "appId";
    /** request attribute：属主用户ID */
    public static final String ATTR_USER_ID = "userId";

    private final ApiAppService apiAppService;

    public OpenApiAuthInterceptor(ApiAppService apiAppService) {
        this.apiAppService = apiAppService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        ApiApp app = apiAppService.authByApiKey(apiKey);
        if (app == null || app.getEnabled() == null || app.getEnabled() != ApiApp.ENABLED) {
            response.setStatus(UNAUTHORIZED_CODE);
            response.setContentType(JSON_CONTENT_TYPE);
            response.getWriter().write(UNAUTHORIZED_RESPONSE);
            return false;
        }
        // 以第三方应用的属主身份注入，与普通用户同路径执行业务与配额校验
        request.setAttribute(ATTR_APP_ID, app.getId());
        request.setAttribute(ATTR_USER_ID, app.getUserId());
        return true;
    }
}