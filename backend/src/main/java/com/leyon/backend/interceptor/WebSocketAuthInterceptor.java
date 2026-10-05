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
 * 握手阶段校验 JWT Token（S-10 · v2.80 起强制：缺失/无效一律 401，延迟认证通道已移除），
 * 支持两种传参方式：Authorization 头、URL 查询参数。校验通过后将用户ID存入会话属性。
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
        // S-10（v2.80）：握手必须携带令牌——"免令牌握手 + 首条 auth 帧认证"的延迟通道已随收口移除，
        // 未认证连接从此在握手层就被 401 拒绝，不再有空占连接槽的窗口（原回收器亦随之退役）
        if (token == null || token.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (!jwtUtil.validateAccessToken(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put("userId", jwtUtil.getUserIdFromToken(token));
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