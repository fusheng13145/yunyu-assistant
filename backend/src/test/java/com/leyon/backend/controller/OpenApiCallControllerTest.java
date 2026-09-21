package com.leyon.backend.controller;

import com.leyon.backend.entity.Assistant;
import com.leyon.backend.interceptor.OpenApiAuthInterceptor;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.OrgService;
import com.leyon.backend.service.OutboundCallService;
import com.leyon.backend.service.QuotaService;
import com.leyon.backend.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放 OpenAPI PSTN 外呼控制器单元测试（P2-17）
 * 覆盖：合法请求创建任务返回 PENDING、配额超限 403、越权 403、参数缺失 400
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenApiCallControllerTest {

    @Mock
    private AssistantService assistantService;
    @Mock
    private OrgService orgService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private OutboundCallService outboundCallService;
    @Mock
    private WebhookService webhookService;

    private OpenApiCallController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new OpenApiCallController(assistantService, orgService, quotaService,
                outboundCallService, webhookService);
        request = org.mockito.Mockito.mock(HttpServletRequest.class);
        lenient().when(request.getAttribute(OpenApiAuthInterceptor.ATTR_USER_ID)).thenReturn("u-owner");
        lenient().when(request.getAttribute(OpenApiAuthInterceptor.ATTR_APP_ID)).thenReturn("app-1");
    }

    private Assistant personalAssistant() {
        Assistant a = new Assistant();
        a.setId("a1");
        a.setUserId("u-owner");
        return a;
    }

    @Test
    void call_valid_createsTaskAndReturnsPending() {
        when(assistantService.getById("a1")).thenReturn(personalAssistant());
        when(outboundCallService.create("u-owner", null, "a1", "10086")).thenReturn("t1");
        ResponseEntity<Map<String, Object>> resp =
                controller.call(Map.of("assistantId", "a1", "phoneNumber", "10086"), request);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("taskId")).isEqualTo("t1");
        assertThat(resp.getBody().get("status")).isEqualTo("PENDING");
        verify(outboundCallService).initiateAsync("t1");
        verify(webhookService).dispatch(any(), any(), any());
    }

    @Test
    void call_missingAssistantId_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.call(Map.of("phoneNumber", "10086"), request);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(outboundCallService, never()).create(any(), any(), any(), any());
    }

    @Test
    void call_missingPhone_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.call(Map.of("assistantId", "a1"), request);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void call_assistantNotFound_returns400() {
        when(assistantService.getById("a-x")).thenReturn(null);
        ResponseEntity<Map<String, Object>> resp =
                controller.call(Map.of("assistantId", "a-x", "phoneNumber", "10086"), request);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void call_foreignAssistant_returns403() {
        Assistant other = new Assistant();
        other.setId("a2");
        other.setUserId("u-other");
        when(assistantService.getById("a2")).thenReturn(other);
        ResponseEntity<Map<String, Object>> resp =
                controller.call(Map.of("assistantId", "a2", "phoneNumber", "10086"), request);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void call_quotaExceeded_returns403() {
        when(assistantService.getById("a1")).thenReturn(personalAssistant());
        org.mockito.Mockito.doThrow(new com.leyon.backend.common.QuotaExceededException("单日通话次数已达上限"))
                .when(quotaService).checkStartCall("u-owner");
        ResponseEntity<Map<String, Object>> resp =
                controller.call(Map.of("assistantId", "a1", "phoneNumber", "10086"), request);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        verify(outboundCallService, never()).create(any(), any(), any(), any());
    }
}