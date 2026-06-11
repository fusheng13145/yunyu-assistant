package com.leyon.backend.config;

import com.leyon.backend.util.JwtUtil;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;

    public WebSocketAuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        // 从 Sec-WebSocket-Protocol / Authorization 头 / 查询参数中获取 token
        String token = extractToken(request);

        if (token == null || token.isEmpty() || !jwtUtil.validateToken(token)) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }

        String userId = jwtUtil.getUserIdFromToken(token);
        attributes.put("userId", userId);

        // 将 Sec-WebSocket-Protocol 原样返回给客户端（否则浏览器会拒绝握手）
        String protocol = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
        if (protocol != null && !protocol.isEmpty()) {
            response.getHeaders().set("Sec-WebSocket-Protocol", protocol);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private String extractToken(ServerHttpRequest request) {
        // 优先从 Sec-WebSocket-Protocol 头获取（前端通过 new WebSocket(url, [token]) 传递）
        String protocol = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
        if (protocol != null && !protocol.isEmpty() && jwtUtil.validateToken(protocol)) {
            return protocol;
        }
        // 其次从 Authorization 请求头获取
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // 最后从查询参数获取（兼容旧版客户端）
        String query = request.getURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "token".equals(kv[0])) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}
