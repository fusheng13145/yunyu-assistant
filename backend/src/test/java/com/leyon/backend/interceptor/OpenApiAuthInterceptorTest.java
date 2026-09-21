package com.leyon.backend.interceptor;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放 OpenAPI 鉴权拦截器单元测试（P2-10 开放 OpenAPI）
 * 覆盖：无 Key 401 / 无效 Key 401 / 停用应用 401 / 有效 Key 放行并注入属主身份
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenApiAuthInterceptorTest {

    @Mock
    private ApiAppService apiAppService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private OpenApiAuthInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new OpenApiAuthInterceptor(apiAppService);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    private ApiApp app(String id, String userId, int enabled) {
        ApiApp app = new ApiApp();
        app.setId(id);
        app.setUserId(userId);
        app.setEnabled(enabled);
        return app;
    }

    @Test
    void noApiKey_rejected401() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn(null);
        boolean pass = interceptor.preHandle(request, response, new Object());
        assertThat(pass).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    void invalidApiKey_rejected401() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("bad-key");
        when(apiAppService.authByApiKey("bad-key")).thenReturn(null);
        boolean pass = interceptor.preHandle(request, response, new Object());
        assertThat(pass).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    void disabledApp_rejected401() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("key-disabled");
        when(apiAppService.authByApiKey("key-disabled")).thenReturn(app("app-1", "u1", ApiApp.DISABLED));
        boolean pass = interceptor.preHandle(request, response, new Object());
        assertThat(pass).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    void validApiKey_passesAndInjectsIdentity() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("key-ok");
        when(apiAppService.authByApiKey("key-ok")).thenReturn(app("app-1", "u-owner", ApiApp.ENABLED));
        boolean pass = interceptor.preHandle(request, response, new Object());
        assertThat(pass).isTrue();
        verify(request).setAttribute(OpenApiAuthInterceptor.ATTR_APP_ID, "app-1");
        verify(request).setAttribute(OpenApiAuthInterceptor.ATTR_USER_ID, "u-owner");
    }
}