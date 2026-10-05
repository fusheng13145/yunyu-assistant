package com.leyon.backend.interceptor;

import com.leyon.backend.support.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WebSocket 握手鉴权拦截器单测（v2.80 · S-10 收口）
 * 覆盖：无令牌 401、无效令牌 401、refresh 令牌 401、有效 access 令牌注入 userId（query 与 header 双通道）——
 * "免令牌握手 + auth 帧延迟认证"通道移除后，这里就是内部两条 WS 通道的唯一门。
 *
 * @author leyon
 */
class WebSocketAuthInterceptorTest {

    private static final String TEST_SECRET = "unit-test-only-secret-key-32-bytes-or-more!!";

    private com.leyon.backend.util.JwtUtil jwtUtil;
    private WebSocketAuthInterceptor interceptor;
    private final WebSocketHandler wsHandler = mock(WebSocketHandler.class);

    @BeforeEach
    void setUp() {
        jwtUtil = new com.leyon.backend.util.JwtUtil(
                mock(com.leyon.backend.service.TokenBlacklistService.class),
                JwtTestSupport.alwaysLiveCredentials());
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 900_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604_800_000L);
        interceptor = new WebSocketAuthInterceptor(jwtUtil);
    }

    /** servlet mock + 包装器：HandshakeInterceptor 收的是 org.springframework.http.server 侧的请求/响应 */
    private boolean proceed(MockHttpServletRequest request, Map<String, Object> attributes,
                            MockHttpServletResponse response) {
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response), wsHandler, attributes);
    }

    @Test
    void handshakeWithoutToken_isRejectedWith401() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/assistant-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(proceed(request, new HashMap<>(), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void handshakeWithInvalidToken_isRejectedWith401() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/assistant-1?token=not-a-jwt-at-all");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(proceed(request, new HashMap<>(), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void handshakeWithRefreshToken_isRejectedWith401() {
        // 与 HTTP 侧同一口径：握手只认 access 令牌，refresh 不能当 WS 凭据用
        String refreshToken = jwtUtil.generateRefreshToken("u-1", "alice");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/assistant-1?token=" + refreshToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(proceed(request, new HashMap<>(), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void handshakeWithValidTokenInQuery_injectsUserIdAndProceeds() {
        // 用真签名令牌：类型闸门要经过 jjwt 编解码才有证明力（与 JwtUtilAccessTokenTest 同口径）
        String token = jwtUtil.generateToken("u-1", "alice");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/assistant-1?token=" + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();
        assertThat(proceed(request, attributes, response)).isTrue();
        assertThat(attributes).containsEntry("userId", "u-1");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void handshakeWithValidTokenInHeader_injectsUserIdAndProceeds() {
        // 桌面客户端可能用 Authorization 头而非查询参数：两条传参通道都在判据内
        String token = jwtUtil.generateToken("u-2", "bob");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/assistant-1");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();
        assertThat(proceed(request, attributes, response)).isTrue();
        assertThat(attributes).containsEntry("userId", "u-2");
    }
}
