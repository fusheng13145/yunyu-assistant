package com.leyon.backend.task;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.entity.OutboundCall;
import com.leyon.backend.entity.WebhookDelivery;
import com.leyon.backend.mapper.ApiAppMapper;
import com.leyon.backend.service.OutboundCallService;
import com.leyon.backend.service.WebhookService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PSTN 外呼超时自动扫描任务（v2.18）
 * 周期性扫描 outbound_calls 中 DIALING 状态滞留超过 app.pstn.timeout-ms 的任务，
 * 置 FAILED「呼叫超时未接通」并按属主用户关联的应用投递 call.status_changed Webhook。
 * 适用于网关未在预期时间内回调（网关侧呼叫未接通/网关故障）的兜底清理。
 *
 * @author leyon
 */
@Component
public class OutboundCallTimeoutScheduler {

    private static final Logger logger = LoggerFactory.getLogger(OutboundCallTimeoutScheduler.class);

    /** DIALING 滞留超时阈值（毫秒），超过即判定呼叫超时 */
    @Value("${app.pstn.timeout-ms:120000}")
    private long timeoutMs;

    private final OutboundCallService outboundCallService;
    private final WebhookService webhookService;
    private final ApiAppMapper apiAppMapper;

    public OutboundCallTimeoutScheduler(OutboundCallService outboundCallService,
                                        WebhookService webhookService,
                                        ApiAppMapper apiAppMapper) {
        this.outboundCallService = outboundCallService;
        this.webhookService = webhookService;
        this.apiAppMapper = apiAppMapper;
    }

    /**
     * 定时扫描：DIALING 超过阈值 → FAILED + Webhook
     */
    @Scheduled(fixedDelayString = "${app.pstn.scan-interval-ms:30000}")
    public void scanTimeoutCalls() {
        try {
            LocalDateTime before = LocalDateTime.now().minusNanos(timeoutMs * 1_000_000);
            List<OutboundCall> staleCalls = outboundCallService.listDialingOlderThan(before);
            if (staleCalls.isEmpty()) {
                return;
            }
            for (OutboundCall call : staleCalls) {
                outboundCallService.updateStatus(call.getId(), OutboundCall.STATUS_FAILED, "呼叫超时未接通");
                dispatchStatusChanged(call);
                logger.info("外呼超时已置失败，taskId:{}", call.getId());
            }
            logger.info("外呼超时扫描完成，处理 {} 条", staleCalls.size());
        } catch (Exception e) {
            logger.error("外呼超时扫描异常", e);
        }
    }

    /**
     * 按属主用户关联其全部应用投递 call.status_changed（复用 PstnCallbackController 投递模式；
     * 应用查不到（内部调用/无配置）时 dispatch 内部跳过）
     */
    private void dispatchStatusChanged(OutboundCall call) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", call.getId());
        payload.put("status", OutboundCall.STATUS_FAILED);
        payload.put("failReason", "呼叫超时未接通");
        List<ApiApp> apps = apiAppMapper.selectList(new LambdaQueryWrapper<ApiApp>()
                .eq(ApiApp::getUserId, call.getUserId()));
        for (ApiApp app : apps) {
            webhookService.dispatch(WebhookDelivery.EVENT_CALL_STATUS_CHANGED, app.getId(), payload);
        }
    }
}