package com.leyon.backend.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.annotation.Audit;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.service.AuditLogService;
import com.leyon.backend.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 IP 落库口径单测（v2.44）
 * <p>
 * 审计的价值在于事后能把行为指认到同一个来源上，因此它与限流桶键、登录锁定的来源维度
 * 必须共用 {@link ClientIpResolver}：v2.44 之前 {@code AuditAspect} 自己直取
 * X-Forwarded-For 第一段，等于被审计者可以随意填写落库的 ip 字段（两次攻击能被记成
 * 两个不同来源）。这里只锁 IP 口径，其余审计形状不在本批范围。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class AuditAspectClientIpTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    /** 只为拿一份真实的 @Audit 注解实例，不作为测试目标 */
    @Audit(action = "LOGIN", targetType = "user")
    private void auditedSample(HttpServletRequest request) {
    }

    private Audit audit() throws Exception {
        return AuditAspectClientIpTest.class
                .getDeclaredMethod("auditedSample", HttpServletRequest.class)
                .getAnnotation(Audit.class);
    }

    private String recordedIp(AuditAspect aspect, Object... args) throws Throwable {
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed()).thenReturn("ok");
        aspect.around(joinPoint, audit());
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue().getIp();
    }

    private MockHttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return request;
    }

    private AuditAspect aspect(boolean trustProxy, int trustHops) {
        return new AuditAspect(auditLogService, new ObjectMapper(), new ClientIpResolver(trustProxy, trustHops));
    }

    @Test
    @DisplayName("默认（不信任代理）：落库 IP 是连接层地址，伪造的 XFF 不进去")
    void forgedForwardedForNeverReachesAuditRow() throws Throwable {
        String ip = recordedIp(aspect(false, 1), request("203.0.113.7", "1.1.1.1"));
        assertThat(ip).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("启用反代信任：落库 IP 取 XFF 右起第一跳，与限流桶键同一判据")
    void trustedProxyRecordsRightmostSegment() throws Throwable {
        String ip = recordedIp(aspect(true, 1), request("10.0.0.2", "1.1.1.1, 203.0.113.8"));
        assertThat(ip).isEqualTo("203.0.113.8");
    }

    @Test
    @DisplayName("非 HTTP 上下文（参数里没有 request）时 IP 为空，不伪造一个占位来源")
    void withoutRequestIpStaysNull() throws Throwable {
        assertThat(recordedIp(aspect(false, 1))).isNull();
    }
}
