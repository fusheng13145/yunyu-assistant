package com.leyon.backend.interceptor;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;

/**
 * OpenAPI 鉴权拦截器（P2-10 开放 OpenAPI）
 * 拦截 /api/open/** 请求，通过 X-API-Key 请求头校验第三方应用（与 AuthInterceptor 完全隔离）
 * 校验通过后注入 appId/userId 到 request attributes，供后续业务按属主身份执行与配额统计
 * <p>
 * v2.45 补上第二道判定：**能力（scopes）按端点校验**。此前这里只看"Key 有效且启用"，
 * 而 {@code ApiApp.scopes} 全仓没有一个读判点——于是"一个 Key 只能文本对话"从来不是事实，
 * 拿到任意 Key 的第三方都能打外呼（花钱的那条链路）。缺能力回 **403 并写明缺哪一个**，
 * 不回 401：401 会让调用方去轮换一把同样没有权限的 Key，排障方向整个错掉。
 * <p>
 * 未出现在 {@link #REQUIRED_SCOPES} 里的 /api/open/ 路径按**拒绝**处理并记 WARN：
 * 新增开放端点时忘了登记能力，表现必须是"立刻被发现"，而不是"所有 Key 都能调"。
 * （/api/open/callbacks/** 由 AppConfig 从本拦截器排除，走独立的 X-Gateway-Token 校验。）
 *
 * @author leyon
 */
@Component
public class OpenApiAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OpenApiAuthInterceptor.class);

    /** API Key 请求头名称 */
    private static final String API_KEY_HEADER = "X-API-Key";
    /** 未授权状态码 */
    private static final int UNAUTHORIZED_CODE = 401;
    /** 禁止状态码（Key 有效但能力不足 / 端点未登记能力） */
    private static final int FORBIDDEN_CODE = 403;
    /** 响应内容类型 */
    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";
    /** 未授权统一返回报文 */
    private static final String UNAUTHORIZED_RESPONSE = "{\"code\":401,\"message\":\"API Key 无效或已停用\",\"data\":null}";

    /** 端点 → 所需能力（Ant 模式，顺序无关） */
    private record RequiredScope(String pattern, String scope) {
    }

    /** 开放 HTTP 端点的能力登记表；与 RateLimitService.Tier 的 EXPENSIVE 档一一对应 */
    private static final List<RequiredScope> REQUIRED_SCOPES = List.of(
            new RequiredScope("/api/open/chat", ApiApp.SCOPE_CHAT),
            new RequiredScope("/api/open/call", ApiApp.SCOPE_CALL));

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

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
        String uri = request.getRequestURI();
        String requiredScope = requiredScope(uri);
        if (requiredScope == null) {
            log.warn("开放端点未登记所需能力，按拒绝处理：{}", uri);
            writeForbidden(response, "该开放端点尚未登记所需能力，请联系服务方");
            return false;
        }
        if (!app.hasScope(requiredScope)) {
            log.warn("开放平台能力不足：appId={} 缺 {}，请求 {}", app.getId(), requiredScope, uri);
            writeForbidden(response, "该 API Key 未开通此能力（需要 " + requiredScope + "）");
            return false;
        }
        // 以第三方应用的属主身份注入，与普通用户同路径执行业务与配额校验
        request.setAttribute(ATTR_APP_ID, app.getId());
        request.setAttribute(ATTR_USER_ID, app.getUserId());
        return true;
    }

    /** 该 URI 所需能力；未登记的开放路径返回 null（由调用方按拒绝处理） */
    private static String requiredScope(String uri) {
        for (RequiredScope entry : REQUIRED_SCOPES) {
            if (PATH_MATCHER.match(entry.pattern(), uri)) {
                return entry.scope();
            }
        }
        return null;
    }

    /** 403 报文：把"缺什么"直接说给调用方，避免它去猜是密钥错了还是地址错了 */
    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(FORBIDDEN_CODE);
        response.setContentType(JSON_CONTENT_TYPE);
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\",\"data\":null}");
    }
}