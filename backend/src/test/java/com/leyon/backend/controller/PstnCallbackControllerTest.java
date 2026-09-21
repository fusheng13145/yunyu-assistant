package com.leyon.backend.controller;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.entity.OutboundCall;
import com.leyon.backend.mapper.ApiAppMapper;
import com.leyon.backend.service.OutboundCallService;
import com.leyon.backend.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PSTN 网关回调控制器单元测试（P2-17 开放 OpenAPI 语音外呼）
 * 覆盖：Token 错误 401、有效回调更新状态 + 投递 Webhook、任务不存在 400、非法状态 400
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PstnCallbackControllerTest {

    @Mock
    private OutboundCallService outboundCallService;
    @Mock
    private WebhookService webhookService;
    @Mock
    private ApiAppMapper apiAppMapper;

    private PstnCallbackController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new PstnCallbackController(outboundCallService, webhookService, apiAppMapper);
        Field f = PstnCallbackController.class.getDeclaredField("callbackToken");
        f.setAccessible(true);
        f.set(controller, "gw-token");
        lenient().when(apiAppMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
                .thenReturn(List.of());
    }

    private OutboundCall task(String id, String userId) {
        OutboundCall call = new OutboundCall();
        call.setId(id);
        call.setUserId(userId);
        return call;
    }

    @Test
    void callback_wrongToken_returns401() {
        ResponseEntity<Map<String, Object>> resp =
                controller.pstnCallback(Map.of("taskId", "t1", "status", "ACTIVE"), "bad-token");
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verify(outboundCallService, never()).updateStatus(any(), any(), any());
    }

    @Test
    void callback_missingToken_returns401() {
        ResponseEntity<Map<String, Object>> resp =
                controller.pstnCallback(Map.of("taskId", "t1", "status", "ACTIVE"), null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void callback_valid_updatesStatusAndDispatchesWebhook() {
        when(outboundCallService.getById("t1")).thenReturn(task("t1", "u1"));
        when(outboundCallService.updateStatus("t1", OutboundCall.STATUS_ACTIVE, null)).thenReturn(true);
        ApiApp app = new ApiApp();
        app.setId("app-1");
        app.setUserId("u1");
        when(apiAppMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
                .thenReturn(List.of(app));

        ResponseEntity<Map<String, Object>> resp =
                controller.pstnCallback(Map.of("taskId", "t1", "status", "ACTIVE"), "gw-token");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(outboundCallService).updateStatus("t1", OutboundCall.STATUS_ACTIVE, null);
        verify(webhookService).dispatch(any(), any(), any());
    }

    @Test
    void callback_taskNotFound_returns400() {
        when(outboundCallService.getById("nope")).thenReturn(null);
        ResponseEntity<Map<String, Object>> resp =
                controller.pstnCallback(Map.of("taskId", "nope", "status", "ACTIVE"), "gw-token");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void callback_invalidStatus_returns400() {
        when(outboundCallService.getById("t1")).thenReturn(task("t1", "u1"));
        ResponseEntity<Map<String, Object>> resp =
                controller.pstnCallback(Map.of("taskId", "t1", "status", "PENDING"), "gw-token");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }
}