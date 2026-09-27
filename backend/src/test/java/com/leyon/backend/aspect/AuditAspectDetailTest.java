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
 * 审计"改了什么"的落库单测（v2.46）。
 * <p>
 * 只有 action + targetId 的审计能指认"谁动过哪个应用"，指认不了"动成了什么样"。
 * 本批给 {@code AuditAspect} 加了一条最小机制：被审计方法往 request 放
 * {@code auditDetail}（一个 Map），切面在 proceed **之后**读它并序列化进 {@code audit_logs.detail}。
 * <p>
 * "之后"是这个机制的全部要点：读点挪到 proceed 之前，属性永远是 null，日志照写、
 * 详情恒空——那是最省事也最没人会发现的一种失效。所以这里的反向锚点是刻意留的：
 * 既有 {@code @Audit} 动作在没放属性时必须**维持原形状**（detail 为 null，而不是 {@code "{}"}）。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class AuditAspectDetailTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 只为拿一份真实的 @Audit 注解实例，不作为测试目标 */
    @Audit(action = "API_APP_SCOPES_UPDATE", targetType = "api_app")
    private void auditedSample(HttpServletRequest request, String id) {
    }

    private Audit audit() throws Exception {
        return AuditAspectDetailTest.class
                .getDeclaredMethod("auditedSample", HttpServletRequest.class, String.class)
                .getAnnotation(Audit.class);
    }

    private AuditAspect aspect() {
        return new AuditAspect(auditLogService, objectMapper, new ClientIpResolver(false, 1));
    }

    /**
     * @param handlerBehavior 模拟被审计方法体：通常在里面向 request 写属性
     * @param throwInstead    非空则让方法体抛出（失败路径）
     */
    private AuditLog runWith(MockHttpServletRequest request, Runnable handlerBehavior, RuntimeException throwInstead)
            throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[]{request, "app-1"});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            if (handlerBehavior != null) {
                handlerBehavior.run();
            }
            if (throwInstead != null) {
                throw throwInstead;
            }
            return "ok";
        });
        try {
            aspect().around(joinPoint, audit());
        } catch (RuntimeException expected) {
            // 失败路径：切面记完账必须把异常原样抛出去，不能吞
            assertThat(expected).isSameAs(throwInstead);
        }
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/openapi/apps/app-1/scopes");
        request.setRemoteAddr("203.0.113.7");
        request.setAttribute("userId", "user-1");
        return request;
    }

    @Test
    @DisplayName("成功路径：方法体写的 old→new 落进 detail，且 result=1")
    void writesChangeDetailOnSuccess() throws Throwable {
        MockHttpServletRequest request = request();
        AuditLog log = runWith(request,
                () -> request.setAttribute(AuditAspect.DETAIL_ATTRIBUTE,
                        java.util.Map.of("from", "chat", "to", "chat,call")),
                null);

        assertThat(log.getResult()).isEqualTo(1);
        assertThat(log.getAction()).isEqualTo("API_APP_SCOPES_UPDATE");
        assertThat(log.getTargetId()).isEqualTo("app-1");
        assertThat(log.getDetail())
                .contains("\"from\":\"chat\"")
                .contains("\"to\":\"chat,call\"");
    }

    @Test
    @DisplayName("失败路径：failReason 与已写入的详情共存，result=0，异常照抛")
    void mergesFailReasonWithDetail() throws Throwable {
        MockHttpServletRequest request = request();
        RuntimeException boom = new IllegalStateException("写库失败");
        AuditLog log = runWith(request,
                () -> request.setAttribute(AuditAspect.DETAIL_ATTRIBUTE, java.util.Map.of("to", "chat,call")),
                boom);

        assertThat(log.getResult()).isEqualTo(0);
        assertThat(log.getDetail()).contains("\"to\":\"chat,call\"").contains("写库失败");
    }

    @Test
    @DisplayName("反向锚点：没放属性的既有动作维持原形状——detail 为 null 而不是 {}")
    void unchangedForActionsWithoutDetail() throws Throwable {
        AuditLog log = runWith(request(), null, null);

        assertThat(log.getDetail()).isNull();
        assertThat(log.getResult()).isEqualTo(1);
    }

    @Test
    @DisplayName("非 Map 的属性值不进 detail：机制不接受任意对象的隐式序列化")
    void ignoresNonMapAttribute() throws Throwable {
        MockHttpServletRequest request = request();
        AuditLog log = runWith(request,
                () -> request.setAttribute(AuditAspect.DETAIL_ATTRIBUTE, "chat,call"),
                null);

        assertThat(log.getDetail()).isNull();
    }

    @Test
    @DisplayName("详情序列化失败只丢详情：审计行照落，指认不因此消失")
    void serializationFailureKeepsAuditRow() throws Throwable {
        MockHttpServletRequest request = request();
        // 值类型 Jackson 无法序列化（默认配置下抛 InvalidDefinitionException）
        java.util.Map<String, Object> unserializable = java.util.Map.of("to", new Object());

        AuditLog log = runWith(request, () -> request.setAttribute(AuditAspect.DETAIL_ATTRIBUTE, unserializable), null);

        assertThat(log.getAction()).isEqualTo("API_APP_SCOPES_UPDATE");
        assertThat(log.getTargetId()).isEqualTo("app-1");
        assertThat(log.getResult()).isEqualTo(1);
        assertThat(log.getDetail()).isNull();
    }

    @Test
    @DisplayName("切面不把业务返回值吞掉：around 原样返回 proceed 的结果")
    void returnsHandlerResult() throws Throwable {
        MockHttpServletRequest request = request();
        when(joinPoint.getArgs()).thenReturn(new Object[]{request, "app-1"});
        when(joinPoint.proceed()).thenReturn("the-result");

        assertThat(aspect().around(joinPoint, audit())).isEqualTo("the-result");
    }
}
