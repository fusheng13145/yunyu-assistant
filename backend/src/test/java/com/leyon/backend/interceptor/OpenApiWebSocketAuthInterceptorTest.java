package com.leyon.backend.interceptor;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放 OpenAPI 语音 WebSocket 握手鉴权拦截器单元测试（v2.16 开放语音）
 * 覆盖：无 Key 401、apiKey 查询参数有效注入身份、X-API-Key 请求头有效注入、无效/停用 Key 401
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenApiWebSocketAuthInterceptorTest {

    @Mock
    private ApiAppService apiAppService;
    @Mock
    private ServerHttpRequest request;
    @Mock
    private ServerHttpResponse response;
    @Mock
    private WebSocketHandler wsHandler;

    private OpenApiWebSocketAuthInterceptor interceptor;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        interceptor = new OpenApiWebSocketAuthInterceptor(apiAppService);
        attributes = new HashMap<>();
        when(request.getHeaders()).thenReturn(new HttpHeaders());
    }

    private ApiApp app(String id, String userId, int enabled) {
        ApiApp app = new ApiApp();
        app.setId(id);
        app.setUserId(userId);
        app.setEnabled(enabled);
        return app;
    }

    @Test
    void noApiKey_rejected401() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/api/open/ws-voice/a1"));
        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);
        assertThat(pass).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apiKeyQueryParam_valid_passesAndInjectsIdentity() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/api/open/ws-voice/a1?apiKey=key-ok"));
        when(apiAppService.authByApiKey("key-ok")).thenReturn(app("app-1", "u-owner", ApiApp.ENABLED));

        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(pass).isTrue();
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_USER_ID)).isEqualTo("u-owner");
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_APP_ID)).isEqualTo("app-1");
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_OPEN_API)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void apiKeyHeader_valid_passesAndInjectsIdentity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "key-header-ok");
        when(request.getHeaders()).thenReturn(headers);
        when(apiAppService.authByApiKey("key-header-ok")).thenReturn(app("app-2", "u-owner-2", ApiApp.ENABLED));

        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(pass).isTrue();
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_USER_ID)).isEqualTo("u-owner-2");
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_APP_ID)).isEqualTo("app-2");
    }

    @Test
    void invalidApiKey_rejected401() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/api/open/ws-voice/a1?apiKey=bad-key"));
        when(apiAppService.authByApiKey("bad-key")).thenReturn(null);

        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(pass).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
    }

    @Test
    void disabledApp_rejected401() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/api/open/ws-voice/a1?apiKey=key-disabled"));
        when(apiAppService.authByApiKey("key-disabled")).thenReturn(app("app-3", "u-owner", ApiApp.DISABLED));

        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(pass).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}