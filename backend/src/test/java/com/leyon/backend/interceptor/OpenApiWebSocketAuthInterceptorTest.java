package com.leyon.backend.interceptor;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import com.leyon.backend.service.RateLimitService;
import com.leyon.backend.util.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放 OpenAPI 语音 WebSocket 握手鉴权拦截器单元测试（v2.16 开放语音）
 * 覆盖：无 Key 401、apiKey 查询参数有效注入身份、X-API-Key 请求头有效注入、无效/停用 Key 401；
 * v2.45 追加两条：缺 voice 能力 403、握手超限 429 且不查库（限流必须排在验 Key 之前）。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenApiWebSocketAuthInterceptorTest {

    /** 三档全开的应用形态：既有链路用例只关心"能不能握手"，不承担能力判定断言 */
    private static final String ALL_SCOPES = "chat,call,voice";

    @Mock
    private ApiAppService apiAppService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ServerHttpRequest request;
    @Mock
    private ServerHttpResponse response;
    @Mock
    private WebSocketHandler wsHandler;

    private OpenApiWebSocketAuthInterceptor interceptor;
    private Map<String, Object> attributes;

    /** 握手拒绝时要写的响应头：mock 只提供实例，断言直接读它（比 verify 更适合大小写不敏感的 header 语义） */
    private HttpHeaders responseHeaders;

    @BeforeEach
    void setUp() {
        // 来源地址判定用真实组件（默认不信任代理头），只测拦截器自己的编排
        interceptor = new OpenApiWebSocketAuthInterceptor(apiAppService, rateLimitService,
                new ClientIpResolver(false, 1));
        attributes = new HashMap<>();
        responseHeaders = new HttpHeaders();
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        when(response.getHeaders()).thenReturn(responseHeaders);
        allowHandshake();
    }

    /** 放行限流档（各用例只断言鉴权与能力，限流本身在 RateLimitServiceTest 与下面的专项用例里测） */
    private void allowHandshake() {
        when(rateLimitService.tryAcquire(any(RateLimitService.Tier.class), any()))
                .thenReturn(new RateLimitService.Decision(true, 0L));
    }

    private ApiApp app(String id, String userId, int enabled, String scopes) {
        ApiApp app = new ApiApp();
        app.setId(id);
        app.setUserId(userId);
        app.setEnabled(enabled);
        app.setScopes(scopes);
        return app;
    }

    private boolean handshake(String uri, ApiApp app) {
        when(request.getURI()).thenReturn(URI.create(uri));
        when(apiAppService.authByApiKey(any())).thenReturn(app);
        return interceptor.beforeHandshake(request, response, wsHandler, attributes);
    }

    @Test
    @DisplayName("无 Key：401 且不注入身份")
    void noApiKey_rejected401() {
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/api/open/ws-voice/a1"));
        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);
        assertThat(pass).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("apiKey 查询参数 + voice 能力：放行并注入属主身份")
    void apiKeyQueryParam_valid_passesAndInjectsIdentity() {
        boolean pass = handshake("ws://localhost:8080/api/open/ws-voice/a1?apiKey=key-ok",
                app("app-1", "u-owner", ApiApp.ENABLED, ALL_SCOPES));

        assertThat(pass).isTrue();
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_USER_ID)).isEqualTo("u-owner");
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_APP_ID)).isEqualTo("app-1");
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_OPEN_API)).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("X-API-Key 请求头 + voice 能力：放行并注入属主身份")
    void apiKeyHeader_valid_passesAndInjectsIdentity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "key-header-ok");
        when(request.getHeaders()).thenReturn(headers);
        boolean pass = handshake("ws://localhost:8080/api/open/ws-voice/a2",
                app("app-2", "u-owner-2", ApiApp.ENABLED, ALL_SCOPES));

        assertThat(pass).isTrue();
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_USER_ID)).isEqualTo("u-owner-2");
        assertThat(attributes.get(OpenApiWebSocketAuthInterceptor.SESSION_ATTR_APP_ID)).isEqualTo("app-2");
    }

    @Test
    @DisplayName("无效 Key：401")
    void invalidApiKey_rejected401() {
        boolean pass = handshake("ws://localhost:8080/api/open/ws-voice/a1?apiKey=bad-key", null);

        assertThat(pass).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("停用应用：401")
    void disabledApp_rejected401() {
        boolean pass = handshake("ws://localhost:8080/api/open/ws-voice/a1?apiKey=key-disabled",
                app("app-3", "u-owner", ApiApp.DISABLED, ALL_SCOPES));

        assertThat(pass).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("v2.45 能力判定：Key 有效但没有 voice → 403（不是 401，避免调用方去换一把同样没权限的 Key）")
    void missingVoiceScope_rejected403() {
        boolean pass = handshake("ws://localhost:8080/api/open/ws-voice/a1?apiKey=key-no-voice",
                app("app-4", "u-owner", ApiApp.ENABLED, "chat,call"));

        assertThat(pass).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        verify(response, never()).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("v2.45 握手限流：超限直接 429 + Retry-After，且不查库（限流排在验 Key 之前）")
    void rateLimited_rejected429WithoutTouchingDatabase() {
        when(rateLimitService.tryAcquire(eq(RateLimitService.Tier.OPEN_WS), any()))
                .thenReturn(new RateLimitService.Decision(false, 37L));
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/api/open/ws-voice/a1?apiKey=key-ok"));

        boolean pass = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(pass).isFalse();
        verify(response).setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(responseHeaders.getFirst("Retry-After")).isEqualTo("37");
        verify(apiAppService, never()).authByApiKey(any());
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("v2.45 限流按档而非按路径：OPEN_WS 档不注册任何 MVC 路径模式，握手必须自己计数")
    void openWsTierIsNotReachableThroughMvc() {
        // 这条断言锁的是"为什么要在握手拦截器里手动调 RateLimitService"：
        // 以为把 /api/open/ws-voice/* 写进档位表就限上了流，是这个缺陷的复发形态
        assertThat(RateLimitService.Tier.resolveMvc("/api/open/ws-voice/a1")).isNull();
        assertThat(RateLimitService.Tier.OPEN_WS.patternsForMvc()).isEmpty();
        assertThat(Arrays.stream(RateLimitService.Tier.mvcPathPatterns())
                .anyMatch(pattern -> pattern.contains("ws-voice"))).isFalse();
    }

    @Test
    @DisplayName("v2.45 握手桶键经 ClientIpResolver：默认只信连接层地址，开信任后才按 XFF 右起跳数取值")
    void handshakeBucketUsesTrustedSource() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "1.1.1.1");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/api/open/ws-voice/a1?apiKey=key-ok"));
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getLoopbackAddress(), 51234));

        interceptor.beforeHandshake(request, response, wsHandler, attributes);

        ArgumentCaptor<String> bucketIp = ArgumentCaptor.forClass(String.class);
        verify(rateLimitService).tryAcquire(eq(RateLimitService.Tier.OPEN_WS), bucketIp.capture());
        // 连接层地址是回环（127.0.0.1）而不是 XFF 里的 1.1.1.1：握手与 HTTP 侧共用同一份代理信任判据，
        // 不是各读各的头——那正是 v2.44 缺陷在握手上可能的复发形态
        assertThat(bucketIp.getValue()).isEqualTo(InetAddress.getLoopbackAddress().getHostAddress());
    }

    @Test
    @DisplayName("v2.45 启用反代信任后，握手桶键改用 XFF 右起第一跳（否则所有用户会塌进代理地址一个桶）")
    void handshakeBucketHonoursProxyTrust() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "9.9.9.9, 203.0.113.60");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("ws://localhost:8080/api/open/ws-voice/a1?apiKey=key-ok"));
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(InetAddress.getLoopbackAddress(), 51234));
        interceptor = new OpenApiWebSocketAuthInterceptor(apiAppService, rateLimitService,
                new ClientIpResolver(true, 1));

        interceptor.beforeHandshake(request, response, wsHandler, attributes);

        ArgumentCaptor<String> bucketIp = ArgumentCaptor.forClass(String.class);
        verify(rateLimitService).tryAcquire(eq(RateLimitService.Tier.OPEN_WS), bucketIp.capture());
        assertThat(bucketIp.getValue()).isEqualTo("203.0.113.60");
    }
}
