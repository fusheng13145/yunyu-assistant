package com.leyon.backend.interceptor;

import com.leyon.backend.service.RateLimitService;
import com.leyon.backend.util.ClientIpResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RateLimitInterceptor 单测（v2.35 限流扩面）
 * <p>
 * 锁死两件事：①认证类端点（login/register/refresh/password）共用一个 5 次/分钟 的每 IP 桶，
 * 加 refresh 不得让 login+register 的既有强度变松；②高成本端点（OpenAPI 对话/外呼、
 * RAGFlow 检索试验、录音上传）进入 30 次/分钟 的独立桶，普通业务路径完全不限。
 * 走内存实现（redisEnabled 默认 false），断言的是真实判定与 429 响应形状。
 * v2.44 起桶键只取连接层地址（trust-proxy 默认 false），故另有两组用例锁"伪造代理头换不到新桶"
 * 与"启用信任后按右端起跳数取值"。
 * <p>
 * v2.45 把计数实现抽到 {@link RateLimitService}（握手链路要用同一份），本类因此只测
 * "URI 归档 + 429 形状 + 来源地址"这三件拦截器 own 的事；容量滑窗与档间隔离的直接断言
 * 在 {@code RateLimitServiceTest}。
 *
 * @author leyon
 */
class RateLimitInterceptorTest {

    /** 默认形态：不信任代理头，桶键＝连接层地址 */
    private final RateLimitInterceptor interceptor = interceptorWith(false, 1);

    /**
     * v2.44 起信任配置只在 {@link ClientIpResolver} 一处，v2.45 起计数只在 {@link RateLimitService}
     * 一处，拦截器都是构造注入，因此测试直接组装而不是反射填字段
     */
    private RateLimitInterceptor interceptorWith(boolean trustProxy, int trustHops) {
        return new RateLimitInterceptor(new RateLimitService(), new ClientIpResolver(trustProxy, trustHops));
    }

    /**
     * v2.44 起桶键取自连接层地址，故这里必须设 remoteAddr 而不是代理头。
     * xff 非空时仍一并写入请求头，用来验证"该头会不会被误信"。
     */
    private boolean allow(String method, String uri, String ip) throws Exception {
        return allow(interceptor, method, uri, ip, null);
    }

    private boolean allow(String method, String uri, String ip, String xff) throws Exception {
        return allow(interceptor, method, uri, ip, xff);
    }

    private boolean allow(RateLimitInterceptor target, String method, String uri, String remoteAddr, String xff) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (remoteAddr != null) {
            request.setRemoteAddr(remoteAddr);
        }
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        return target.preHandle(request, response, new Object());
    }

    private MockHttpServletResponse blockResponse(String method, String uri, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()), "该请求应已被限流");
        return response;
    }

    @Test
    @DisplayName("登录第 6 次命中 429，且带 Retry-After 与统一 JSON 报文（v2.35 前既有强度不回退）")
    void sixthLoginIsBlockedWith429() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertTrue(allow("POST", "/api/auth/login", "10.0.0.1"), "第 " + (i + 1) + " 次登录应放行");
        }
        MockHttpServletResponse response = blockResponse("POST", "/api/auth/login", "10.0.0.1");
        assertEquals(429, response.getStatus());
        assertNotNull(response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("\"code\":429"));
    }

    @Test
    @DisplayName("refresh 并入认证桶：5 次登录后第 6 次 refresh 即 429（共享桶，加面不摊薄既有保护）")
    void refreshSharesAuthBucket() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertTrue(allow("POST", "/api/auth/login", "10.0.0.2"));
        }
        assertFalse(allow("POST", "/api/auth/refresh", "10.0.0.2"), "refresh 应受同一认证桶约束");
    }

    @Test
    @DisplayName("register 与 password 同样在认证桶内（改密是爆破目标，v2.35 前只有 login/register）")
    void registerAndPasswordAreAuthTier() throws Exception {
        assertTrue(allow("POST", "/api/auth/register", "10.0.0.3"));
        assertTrue(allow("PUT", "/api/auth/password", "10.0.0.4"));
        for (int i = 0; i < 5; i++) {
            assertTrue(allow("PUT", "/api/auth/password", "10.0.0.5"), "password 前 5 次应放行");
        }
        assertFalse(allow("POST", "/api/auth/login", "10.0.0.5"), "password 的 5 次应已耗尽该 IP 的认证桶");
    }

    @Test
    @DisplayName("OpenAPI 对话进入高成本桶：第 31 次 429，第 30 次仍放行")
    void openChatUsesExpensiveTier() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertTrue(allow("POST", "/api/open/chat", "10.0.0.6"), "第 " + (i + 1) + " 次应放行");
        }
        MockHttpServletResponse response = blockResponse("POST", "/api/open/chat", "10.0.0.6");
        assertEquals(429, response.getStatus());
    }

    @Test
    @DisplayName("高成本桶跨端点共享：外呼+检索试验+带路径变量的录音上传同池计量")
    void expensiveTierSharedAcrossEndpoints() throws Exception {
        allow("POST", "/api/open/call", "10.0.0.7");
        allow("POST", "/api/ragflow/retrieval-test", "10.0.0.7");
        for (int i = 0; i < 28; i++) {
            assertTrue(allow("POST", "/api/call-records/c" + i + "/recording", "10.0.0.7"));
        }
        assertFalse(allow("POST", "/api/open/call", "10.0.0.7"), "30 次额度应已被同桶端点耗尽");
    }

    @Test
    @DisplayName("普通业务路径完全不限（/api/assistants 连打 100 次不触发）")
    void ordinaryPathsAreNotRateLimited() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertTrue(allow("GET", "/api/assistants", "10.0.0.8"));
        }
    }

    @Test
    @DisplayName("OPTIONS 预检放行且不计数")
    void optionsPreflightIsExempt() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertTrue(allow("OPTIONS", "/api/auth/login", "10.0.0.9"));
        }
        for (int i = 0; i < 5; i++) {
            assertTrue(allow("POST", "/api/auth/login", "10.0.0.9"));
        }
        assertFalse(allow("POST", "/api/auth/login", "10.0.0.9"));
    }

    @Test
    @DisplayName("不同 IP 各自成桶，互不串扰")
    void bucketsArePerClientIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            allow("POST", "/api/auth/login", "10.0.0.10");
        }
        assertFalse(allow("POST", "/api/auth/login", "10.0.0.10"));
        assertTrue(allow("POST", "/api/auth/login", "10.99.0.10"), "另一 IP 不应被连坐");
    }

    @Test
    @DisplayName("录音下载（GET 同路径形状）也在高成本桶——上传与下载同池，防带宽滥用")
    void recordingDownloadSharesExpensiveBucket() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertTrue(allow("GET", "/api/call-records/c1/recording", "10.0.0.11"));
        }
        assertFalse(allow("GET", "/api/call-records/c1/recording", "10.0.0.11"));
    }

    @Test
    @DisplayName("未启用反代信任（默认）：伪造 XFF 换不到新桶，同连接地址照常 429（v2.44 缺陷正身）")
    void forgedForwardedForCannotEvadeBucket() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertTrue(allow("POST", "/api/auth/login", "203.0.113.50", "1.1.1." + i),
                    "第 " + (i + 1) + " 次伪造 XFF 也应落在同一桶");
        }
        assertFalse(allow("POST", "/api/auth/login", "203.0.113.50", "2.2.2.2"),
                "换一条伪造的 XFF 不该重置额度");
    }

    @Test
    @DisplayName("启用反代信任：桶键取 XFF 右端起第一跳，最左侧的伪造段不参与计数")
    void trustedProxyUsesRightmostSegment() throws Exception {
        RateLimitInterceptor proxied = interceptorWith(true, 1);
        for (int i = 0; i < 5; i++) {
            assertTrue(allow(proxied, "POST", "/api/auth/login", "10.0.0.99", "9.9.9.9, 203.0.113.60"),
                    "受信代理追加的真实客户端前 5 次放行");
        }
        assertFalse(allow(proxied, "POST", "/api/auth/login", "10.0.0.99", "8.8.8.8, 203.0.113.60"),
                "改最左伪造段仍应命中同一客户端桶");
        assertTrue(allow(proxied, "POST", "/api/auth/login", "10.0.0.99", "203.0.113.61"),
                "真实客户端地址不同的请求不应被连坐");
    }
}
