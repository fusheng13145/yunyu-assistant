package com.leyon.backend.interceptor;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * 开放 OpenAPI 语音 WebSocket 握手鉴权拦截器（v2.16 开放语音）
 * 拦截 /api/open/ws-voice/* 端点，以 API Key 鉴权（与 JWT 通道隔离）：
 * - 浏览器 WebSocket 无法设置自定义请求头，故优先支持 URL 查询参数 ?apiKey=xxx；
 * - 非浏览器客户端（curl/mobile/server）可附带 X-API-Key 请求头。
 * 校验通过后将第三方应用属主身份（userId/appId）与通道标记写入会话属性，
 * VoiceSignalingHandler 检测到 userId 非空即视为已认证，全链路复用。
 *
 * @author leyon
 */
@Component
public class OpenApiWebSocketAuthInterceptor implements HandshakeInterceptor {

    /** API Key 请求头 */
    private static final String HEADER_API_KEY = "X-API-Key";
    /** URL 查询参数名 */
    private static final String PARAM_API_KEY = "apiKey";

    /** 会话属性：用户ID */
    public static final String SESSION_ATTR_USER_ID = "userId";
    /** 会话属性：第三方应用ID */
    public static final String SESSION_ATTR_APP_ID = "appId";
    /** 会话属性：OpenAPI 通道标记 */
    public static final String SESSION_ATTR_OPEN_API = "openApi";

    private final ApiAppService apiAppService;

    public OpenApiWebSocketAuthInterceptor(ApiAppService apiAppService) {
        this.apiAppService = apiAppService;
    }

    /**
     * 握手前拦截：提取并校验 API Key，通过后注入属主身份到会话属性
     */
    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
        String apiKey = extractApiKey(request);
        // 缺少 Key 直接拒绝（开放语音无延后认证，必须在握手阶段完成）
        if (apiKey == null || apiKey.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        ApiApp app = apiAppService.authByApiKey(apiKey);
        // Key 无效或应用已停用，拒绝握手
        if (app == null || app.getEnabled() == null || app.getEnabled() != ApiApp.ENABLED) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        // 以第三方应用属主身份接入，与普通用户同路径执行助手归属校验与配额统计
        attributes.put(SESSION_ATTR_USER_ID, app.getUserId());
        attributes.put(SESSION_ATTR_APP_ID, app.getId());
        attributes.put(SESSION_ATTR_OPEN_API, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler, @Nullable Exception exception) {
        // 无需处理
    }

    /**
     * 多方式提取 API Key：X-API-Key 请求头优先，其次 URL 查询参数 apiKey
     */
    private String extractApiKey(ServerHttpRequest request) {
        String headerKey = request.getHeaders().getFirst(HEADER_API_KEY);
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey;
        }
        URI uri = request.getURI();
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        return parseApiKeyFromQuery(query);
    }

    /**
     * 解析 URL 查询参数中的 apiKey（app_key 为 UUID hex，无需 URL 解码）
     */
    private String parseApiKeyFromQuery(String query) {
        String[] params = query.split("&");
        for (String param : params) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && PARAM_API_KEY.equals(kv[0]) && !kv[1].isBlank()) {
                return kv[1];
            }
        }
        return null;
    }
}