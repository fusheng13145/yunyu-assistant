package com.leyon.backend.interceptor;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放 OpenAPI 鉴权拦截器单元测试（P2-10 开放 OpenAPI）
 * 覆盖：无 Key 401 / 无效 Key 401 / 停用应用 401 / 有效 Key 且有能力放行并注入属主身份；
 * v2.45 追加能力判定：缺所需能力回 403（不回 401，否则调用方会去换一把同样没权限的 Key），
 * 以及"未登记所需能力的开放路径按拒绝处理"——新增端点忘记登记时必须是立刻可见的失败。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenApiAuthInterceptorTest {

    /** 与 v2.45 之前的实际行为等价的能力集，用于既有链路用例 */
    private static final String ALL_SCOPES = "chat,call,voice";

    @Mock
    private ApiAppService apiAppService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private OpenApiAuthInterceptor interceptor;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new OpenApiAuthInterceptor(apiAppService);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    private ApiApp app(String id, String userId, int enabled, String scopes) {
        ApiApp app = new ApiApp();
        app.setId(id);
        app.setUserId(userId);
        app.setEnabled(enabled);
        app.setScopes(scopes);
        return app;
    }

    /** 以给定 Key 与 URI 走一次拦截，返回是否放行 */
    private boolean pass(String apiKey, String uri, ApiApp app) throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn(apiKey);
        when(request.getRequestURI()).thenReturn(uri);
        when(apiAppService.authByApiKey(apiKey)).thenReturn(app);
        return interceptor.preHandle(request, response, new Object());
    }

    @Test
    @DisplayName("无 Key：401")
    void noApiKey_rejected401() throws Exception {
        assertThat(pass(null, "/api/open/chat", null)).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    @DisplayName("无效 Key：401")
    void invalidApiKey_rejected401() throws Exception {
        assertThat(pass("bad-key", "/api/open/chat", null)).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    @DisplayName("停用应用：401")
    void disabledApp_rejected401() throws Exception {
        assertThat(pass("key-disabled", "/api/open/chat",
                app("app-1", "u1", ApiApp.DISABLED, ALL_SCOPES))).isFalse();
        verify(response).setStatus(401);
    }

    @Test
    @DisplayName("有效 Key + chat 能力调用 /api/open/chat：放行并注入属主身份")
    void validApiKey_passesAndInjectsIdentity() throws Exception {
        boolean allowed = pass("key-ok", "/api/open/chat",
                app("app-1", "u-owner", ApiApp.ENABLED, ALL_SCOPES));

        assertThat(allowed).isTrue();
        verify(request).setAttribute(OpenApiAuthInterceptor.ATTR_APP_ID, "app-1");
        verify(request).setAttribute(OpenApiAuthInterceptor.ATTR_USER_ID, "u-owner");
    }

    @Test
    @DisplayName("v2.45 只有 chat 的 Key 打 /api/open/call：403 且写明缺 call（外呼是花钱链路，不能靠 Key 有效就放行）")
    void chatOnlyKey_cannotCall() throws Exception {
        boolean allowed = pass("key-chat-only", "/api/open/call",
                app("app-2", "u-owner", ApiApp.ENABLED, "chat"));

        assertThat(allowed).isFalse();
        verify(response).setStatus(403);
        assertThat(responseBody.toString()).contains("\"code\":403").contains("call");
    }

    @Test
    @DisplayName("v2.45 scopes 为空的应用一律拒绝：不存在\"空=全部能力\"的兜底")
    void blankScopes_deniedNotTreatedAsAll() throws Exception {
        assertThat(pass("key-no-scope", "/api/open/chat",
                app("app-3", "u-owner", ApiApp.ENABLED, null))).isFalse();
        assertThat(pass("key-blank-scope", "/api/open/chat",
                app("app-4", "u-owner", ApiApp.ENABLED, "  "))).isFalse();
        verify(response, org.mockito.Mockito.times(2)).setStatus(403);
    }

    @Test
    @DisplayName("v2.45 未登记能力的开放路径按拒绝处理（新端点忘了登记 ⇒ 立刻可见，而不是所有 Key 都能调）")
    void unregisteredOpenPath_denied() throws Exception {
        boolean allowed = pass("key-ok", "/api/open/new-thing",
                app("app-5", "u-owner", ApiApp.ENABLED, ALL_SCOPES));

        assertThat(allowed).isFalse();
        verify(response).setStatus(403);
        assertThat(responseBody.toString()).contains("尚未登记");
    }

    @Test
    @DisplayName("v2.45 逗号两侧空格不影响判定：存储由创建侧归一，判定只按 trim 后成员")
    void scopeMatchingIsTolerant() throws Exception {
        assertThat(pass("key-ok", "/api/open/call",
                app("app-6", "u-owner", ApiApp.ENABLED, "call, chat"))).isTrue();
    }
}
