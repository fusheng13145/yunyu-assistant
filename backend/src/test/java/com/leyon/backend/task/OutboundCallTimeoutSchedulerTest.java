package com.leyon.backend.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PSTN 外呼超时自动扫描任务单元测试（v2.18）
 * 覆盖：无滞留任务不处理、有滞留任务置 FAILED 并投递 Webhook、异常捕获不中断
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundCallTimeoutSchedulerTest {

    @Mock
    private OutboundCallService outboundCallService;
    @Mock
    private WebhookService webhookService;
    @Mock
    private ApiAppMapper apiAppMapper;

    private OutboundCallTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new OutboundCallTimeoutScheduler(outboundCallService, webhookService, apiAppMapper);
        Field f = OutboundCallTimeoutScheduler.class.getDeclaredField("timeoutMs");
        f.setAccessible(true);
        f.set(scheduler, 120000L);
        lenient().when(apiAppMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    }

    private OutboundCall dialing(String id, String userId) {
        OutboundCall call = new OutboundCall();
        call.setId(id);
        call.setUserId(userId);
        call.setStatus(OutboundCall.STATUS_DIALING);
        call.setStartedAt(LocalDateTime.now().minusMinutes(5));
        return call;
    }

    @Test
    void scan_noStaleCalls_noop() {
        when(outboundCallService.listDialingOlderThan(any(LocalDateTime.class))).thenReturn(List.of());
        scheduler.scanTimeoutCalls();
        verify(outboundCallService, never()).updateStatus(any(), any(), any());
        verify(webhookService, never()).dispatch(any(), any(), any());
    }

    @Test
    void scan_staleCall_marksFailedAndDispatchesWebhook() {
        OutboundCall stale = dialing("t1", "u1");
        when(outboundCallService.listDialingOlderThan(any(LocalDateTime.class))).thenReturn(List.of(stale));
        when(outboundCallService.updateStatus("t1", OutboundCall.STATUS_FAILED, "呼叫超时未接通")).thenReturn(true);
        ApiApp app = new ApiApp();
        app.setId("app-1");
        app.setUserId("u1");
        when(apiAppMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(app));

        scheduler.scanTimeoutCalls();

        verify(outboundCallService).updateStatus("t1", OutboundCall.STATUS_FAILED, "呼叫超时未接通");
        verify(webhookService)
                .dispatch(eq(com.leyon.backend.entity.WebhookDelivery.EVENT_CALL_STATUS_CHANGED), eq("app-1"), any());
    }

    @Test
    void scan_error_doesNotBreakLoop() {
        when(outboundCallService.listDialingOlderThan(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("db error"));
        // 不应抛出（内部兜底捕获）
        scheduler.scanTimeoutCalls();
    }
}