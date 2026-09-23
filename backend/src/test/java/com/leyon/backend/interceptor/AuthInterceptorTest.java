package com.leyon.backend.interceptor;

import com.leyon.backend.service.TokenBlacklistService;
import com.leyon.backend.util.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JWT 认证拦截器单元测试（v2.32 全链路验证产出）
 * 覆盖：access 放行并回填 userId、refresh 令牌当 Bearer 被拒、坏/过期令牌被拒、无头 401、OPTIONS 放行
 * <p>
 * 用真实 JwtUtil（只注入配置与黑名单 mock）：本批要证明的是"类型闸门在鉴权入口处真的生效"，
 * 把 JwtUtil mock 掉就只能证明调用了某个方法。
 *
 * @author leyon
 */
class AuthInterceptorTest {

    /** 测试专用密钥（非任何环境真实凭据） */
    private static final String TEST_SECRET = "unit-test-only-secret-key-32-bytes-or-more!!";

    private AuthInterceptor interceptor;
    private JwtUtil jwtUtil;
    private TokenBlacklistService blacklistService;

    @BeforeEach
    void setUp() {
        blacklistService = mock(TokenBlacklistService.class);
        when(blacklistService.isBlacklisted(any())).thenReturn(false);
        jwtUtil = new JwtUtil(blacklistService);
        ReflectionTestUtils.setField(jwtUtil, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604_800_000L);
        jwtUtil.validateSecret();
        interceptor = new AuthInterceptor(jwtUtil);
    }

    private MockHttpServletRequest request(String authHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/assistants");
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        return request;
    }

    private boolean preHandle(MockHttpServletRequest request, MockHttpServletResponse response) {
        try {
            return interceptor.preHandle(request, response, new Object());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void accessTokenPassesAndFillsUserIdAttribute() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = request("Bearer " + jwtUtil.generateToken("u-1", "tester"));
        assertThat(preHandle(request, response)).isTrue();
        assertThat(request.getAttribute("userId")).isEqualTo("u-1");
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void refreshTokenUsedAsBearerIsRejectedWith401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String refresh = jwtUtil.generateRefreshToken("u-1", "tester");
        // 前提：刷新令牌自身有效（否则这条测试没区分出"类型"这一层）
        assertThat(jwtUtil.validateToken(refresh)).isTrue();
        assertThat(preHandle(request("Bearer " + refresh), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("\"code\":401");
    }

    @Test
    void blacklistedAccessTokenIsRejected() {
        String access = jwtUtil.generateToken("u-1", "tester");
        when(blacklistService.isBlacklisted(jwtUtil.getJtiFromToken(access))).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(request("Bearer " + access), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void malformedAndForeignSignedTokensAreRejected() {
        for (String value : new String[]{"Bearer ", "Bearer not-a-jwt", "Basic YWxhZGRpbjpvcGVuc2VzYW1l"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(preHandle(request(value), response)).isFalse();
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Test
    void missingHeaderIsRejected() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(request(null), response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void optionsPreflightIsAllowedWithoutToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/assistants");
        assertThat(preHandle(request, new MockHttpServletResponse())).isTrue();
    }

    @Test
    void expiredAccessTokenIsRejected() {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1_000L);
        String expired = jwtUtil.generateToken("u-1", "tester");
        jwtUtil.validateSecret();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(preHandle(request("Bearer " + expired), response)).isFalse();
    }
}
