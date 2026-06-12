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
 * 握手阶段校验 JWT Token，支持三种传参方式：协议头、Authorization、URL查询参数
 * 校验通过后将用户ID存入会话属性
 *
 * @author leyon
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    /** Sec-WebSocket-Protocol 协议头 */
    private static final String HEADER_WS_PROTOCOL = "Sec-WebSocket-Protocol";
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
        // Token 为空或校验失败，拒绝握手
        if (token == null || token.isBlank() || !jwtUtil.validateToken(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        // 解析用户ID并存入会话属性
        String userId = jwtUtil.getUserIdFromToken(token);
        attributes.put("userId", userId);

        // 回传协议头，保证浏览器正常完成WebSocket握手
        String protocol = request.getHeaders().getFirst(HEADER_WS_PROTOCOL);
        if (protocol != null && !protocol.isBlank()) {
            response.getHeaders().set(HEADER_WS_PROTOCOL, protocol);
        }
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
     * 多方式提取Token，优先级：协议头 -> Authorization头 -> URL查询参数
     */
    private String extractToken(ServerHttpRequest request) {
        // 1. 从 WebSocket 协议头获取
        String protocol = request.getHeaders().getFirst(HEADER_WS_PROTOCOL);
        if (protocol != null && !protocol.isBlank() && jwtUtil.validateToken(protocol)) {
            return protocol;
        }

        // 2. 从标准 Authorization 请求头获取
        String authHeader = request.getHeaders().getFirst(HEADER_AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX_LEN);
        }

        // 3. 从 URL 查询参数获取
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