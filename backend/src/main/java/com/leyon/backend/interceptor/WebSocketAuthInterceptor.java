package com.leyon.backend.interceptor;

import com.leyon.backend.util.JwtUtil;
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
 * WebSocket 握手鉴权拦截器
 * 握手阶段校验 JWT Token，支持两种传参方式：Authorization 头、URL 查询参数
 * （已移除 Sec-WebSocket-Protocol 子协议传递方式，改由首条消息认证）
 * 校验通过后将用户ID存入会话属性
 *
 * @author leyon
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    /** 认证请求头 */
    private static final String HEADER_AUTHORIZATION = "Authorization";
    /** Bearer 前缀 */
    private static final String BEARER_PREFIX = "Bearer ";
    /** Bearer 前缀长度 */
    private static final int BEARER_PREFIX_LEN = 7;
    /** URL 参数名 */
    private static final String PARAM_TOKEN = "token";

    private final JwtUtil jwtUtil;

    public WebSocketAuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * 握手前拦截：执行Token鉴权
     */
    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
        String token = extractToken(request);
        // Token 为空时允许握手（延迟到首条消息认证），Token 存在则立即校验
        if (token != null && !token.isBlank() && !jwtUtil.validateToken(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 如果 Token 已在握手阶段提供，直接解析用户ID
        if (token != null && !token.isBlank()) {
            String userId = jwtUtil.getUserIdFromToken(token);
            attributes.put("userId", userId);
        }
        // 否则 userId 将在 ChatWebSocketHandler 收到 auth 消息后设置

        return true;
    }

    /**
     * 握手完成后回调（无业务逻辑）
     */
    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler, @Nullable Exception exception) {
        // 无需处理
    }

    /**
     * 多方式提取Token，优先级：Authorization头 -> URL查询参数
     * （已移除 Sec-WebSocket-Protocol 子协议方式）
     */
    private String extractToken(ServerHttpRequest request) {
        // 1. 从标准 Authorization 请求头获取
        String authHeader = request.getHeaders().getFirst(HEADER_AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX_LEN);
        }

        // 2. 从 URL 查询参数获取
        URI uri = request.getURI();
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }

        return parseTokenFromQuery(query);
    }

    /**
     * 解析URL查询参数，提取 token 值
     */
    private String parseTokenFromQuery(String query) {
        String[] params = query.split("&");
        for (String param : params) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && PARAM_TOKEN.equals(kv[0]) && !kv[1].isBlank()) {
                return kv[1];
            }
        }
        return null;
    }
}