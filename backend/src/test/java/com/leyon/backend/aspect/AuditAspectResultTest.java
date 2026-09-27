package com.leyon.backend.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.service.AuditLogService;
import com.leyon.backend.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计"成没成"的落库单测（v2.47 · C-105，收口候选 ㉛）。
 * <p>
 * 判据原本只有一条："有没有抛异常"。但管理侧 19 个 {@code @Audit} 端点的拒绝路径一律返回
 * {@code ApiResponse.paramError(...)}（HTTP 200 + body {@code code:400}）而不是抛异常，
 * 于是被拒绝的变更与成功的变更在库里长得一模一样——"谁在反复试改别人的应用"这条最该被
 * 审计抓住的信号，恰好被记成 {@code result=1}。
 * <p>
 * 断言按"判据换了什么"组织：业务码非 200 才算失败；200、异常、非 ApiResponse 三类各自维持
 * 原状（反向锚点，防止判据被改成"一律失败"这种看着也绿的写法）。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class AuditAspectResultTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Audit(action = "API_APP_SCOPES_UPDATE", targetType = "api_app")
    private void auditedSample(HttpServletRequest request, String id) {
    }

    private Audit audit() throws Exception {
        return AuditAspectResultTest.class
                .getDeclaredMethod("auditedSample", HttpServletRequest.class, String.class)
                .getAnnotation(Audit.class);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/openapi/apps/app-1/scopes");
        request.setRemoteAddr("203.0.113.7");
        request.setAttribute("userId", "user-1");
        return request;
    }

    /**
     * 模拟一次被审计调用，取回切面落库的那一行。
     *
     * @param changeDetail 被审计方法往 request 写的"改了什么"（可为 null）
     * @param returnValue  被审计方法的返回值（可为 null）
     * @param throwInstead 非空则让方法体抛出，验异常路径
     */
    private AuditLog run(Map<String, Object> changeDetail, Object returnValue, RuntimeException throwInstead)
            throws Throwable {
        MockHttpServletRequest request = request();
        when(joinPoint.getArgs()).thenReturn(new Object[]{request, "app-1"});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            if (throwInstead != null) {
                throw throwInstead;
            }
            if (changeDetail != null) {
                request.setAttribute(AuditAspect.DETAIL_ATTRIBUTE, changeDetail);
            }
            return returnValue;
        });
        try {
            new AuditAspect(auditLogService, objectMapper, new ClientIpResolver(false, 1))
                    .around(joinPoint, audit());
        } catch (RuntimeException expected) {
            // 切面记完账必须把异常原样抛出去，不能吞
            assertThat(expected).isSameAs(throwInstead);
        }
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }

    private AuditLog returned(Object returnValue) throws Throwable {
        return run(null, returnValue, null);
    }

    @Nested
    @DisplayName("业务码判据：非 200 即失败")
    class BusinessCodeDecides {

        @Test
        @DisplayName("paramError(400) 记 result=0，并把 message 写进 failReason")
        void paramErrorRecordsFailure() throws Throwable {
            AuditLog log = returned(ApiResponse.paramError("应用不存在或无操作权限"));

            assertThat(log.getResult()).isZero();
            assertThat(log.getDetail()).contains("应用不存在或无操作权限");
        }

        @Test
        @DisplayName("error(500) 同样记 result=0：改密时「原密码不正确」就走这条")
        void systemErrorRecordsFailure() throws Throwable {
            AuditLog log = returned(ApiResponse.error("原密码不正确"));

            assertThat(log.getResult()).isZero();
            assertThat(log.getDetail()).contains("原密码不正确");
        }

        @Test
        @DisplayName("自定义码非 200 也记失败：判据是码值而不是工厂方法名")
        void customCodeRecordsFailure() throws Throwable {
            AuditLog log = returned(ApiResponse.result(403, "无权限"));

            assertThat(log.getResult()).isZero();
        }

        @Test
        @DisplayName("携带 data 的 paramError 不影响判据")
        void paramErrorWithDataRecordsFailure() throws Throwable {
            AuditLog log = returned(ApiResponse.paramError("count 必须是数字", "detail"));

            assertThat(log.getResult()).isZero();
            assertThat(log.getDetail()).contains("count 必须是数字");
        }

        @Test
        @DisplayName("message 为 null 时不炸：仍记 result=0")
        void nullMessageHandled() throws Throwable {
            AuditLog log = returned(new ApiResponse<>(400, null, null));

            assertThat(log.getResult()).isZero();
        }
    }

    @Nested
    @DisplayName("反向锚点：三类返回维持原状")
    class UnchangedShapes {

        @Test
        @DisplayName("code=200 且带数据 ⇒ 仍然 result=1，detail 为 null")
        void successStaysSuccess() throws Throwable {
            AuditLog log = returned(ApiResponse.success(Map.of("token", "t")));

            assertThat(log.getResult()).isEqualTo(1);
            assertThat(log.getDetail()).isNull();
        }

        @Test
        @DisplayName("无数据 success() ⇒ result=1")
        void voidSuccessStaysSuccess() throws Throwable {
            assertThat(returned(ApiResponse.success()).getResult()).isEqualTo(1);
        }

        @Test
        @DisplayName("抛异常 ⇒ result=0 且异常原样抛出（换了判据也不能丢旧判据）")
        void thrownExceptionStillFails() throws Throwable {
            RuntimeException boom = new IllegalStateException("写库失败");
            AuditLog log = run(null, null, boom);

            assertThat(log.getResult()).isZero();
            assertThat(log.getDetail()).contains("写库失败");
        }

        @Test
        @DisplayName("返回 null ⇒ 判据不猜，维持 result=1")
        void nullReturnNotJudged() throws Throwable {
            assertThat(returned(null).getResult()).isEqualTo(1);
        }

        @Test
        @DisplayName("返回非 ApiResponse 类型 ⇒ 判据不猜，维持 result=1")
        void nonApiResponseReturnNotJudged() throws Throwable {
            assertThat(returned("ok").getResult()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("与变更详情共存：被拒绝的那一行也要留痕")
    class DetailCoexistsWithRejection {

        @Test
        @DisplayName("写了 from→to 之后被拒：result=0，from/to 与 failReason 同行共存")
        void keepsChangeDetailOnRejection() throws Throwable {
            AuditLog log = run(Map.of("from", "chat", "to", "chat,call"),
                    ApiResponse.paramError("应用不存在或无操作权限"), null);

            assertThat(log.getResult()).isZero();
            assertThat(log.getDetail())
                    .contains("\"from\":\"chat\"")
                    .contains("\"to\":\"chat,call\"")
                    .contains("应用不存在或无操作权限");
        }

        @Test
        @DisplayName("成功路径不受影响：from/to 在、result=1")
        void keepsChangeDetailOnSuccess() throws Throwable {
            AuditLog log = run(Map.of("from", "chat", "to", "chat,call"), ApiResponse.success(), null);

            assertThat(log.getResult()).isEqualTo(1);
            assertThat(log.getDetail()).contains("\"to\":\"chat,call\"");
        }
    }
}
