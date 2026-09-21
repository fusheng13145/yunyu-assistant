package com.leyon.backend.controller;

import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.entity.OutboundCall;
import com.leyon.backend.entity.WebhookDelivery;
import com.leyon.backend.mapper.ApiAppMapper;
import com.leyon.backend.service.OutboundCallService;
import com.leyon.backend.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PSTN 网关回调接口（P2-17 开放 OpenAPI 语音外呼）
 * 网关发起呼叫后，将后续呼叫状态（接通/完成/失败）回调本端点更新外呼任务并触发 Webhook
 * 鉴权：请求头 X-Gateway-Token 必须等于 app.pstn.callback-token（环境变量 PSTN_CALLBACK_TOKEN），防伪造。
 * 该端点已被 OpenApiAuthInterceptor 排除（AppConfig），不受 X-API-Key 约束。
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/open/callbacks")
public class PstnCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(PstnCallbackController.class);

    /** 网关回调 Token 请求头 */
    private static final String HEADER_GATEWAY_TOKEN = "X-Gateway-Token";

    @Value("${app.pstn.callback-token:}")
    private String callbackToken;

    private final OutboundCallService outboundCallService;
    private final WebhookService webhookService;
    private final ApiAppMapper apiAppMapper;

    public PstnCallbackController(OutboundCallService outboundCallService,
                                  WebhookService webhookService,
                                  ApiAppMapper apiAppMapper) {
        this.outboundCallService = outboundCallService;
        this.webhookService = webhookService;
        this.apiAppMapper = apiAppMapper;
    }

    /**
     * PSTN 网关回调：body { taskId, status, failReason? }
     * status 支持 ACTIVE/COMPLETED/FAILED 等任务状态
     */
    @PostMapping("/pstn")
    public ResponseEntity<Map<String, Object>> pstnCallback(@RequestBody Map<String, String> body,
                                                            @RequestHeader(value = HEADER_GATEWAY_TOKEN, required = false) String gatewayToken) {
        // Token 校验（未配置 token 时拒绝回调，避免伪造）
        if (!StringUtils.hasText(callbackToken) || !callbackToken.equals(gatewayToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "网关回调 Token 无效"));
        }
        String taskId = body == null ? null : body.get("taskId");
        String status = body == null ? null : body.get("status");
        String failReason = body == null ? null : body.get("failReason");
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(status)) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "taskId 与 status 不能为空"));
        }

        OutboundCall call = outboundCallService.getById(taskId);
        if (call == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "外呼任务不存在"));
        }
        // 校验合法目标状态
        if (!isValidTargetStatus(status)) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "非法的目标状态：" + status));
        }
        boolean updated = outboundCallService.updateStatus(taskId, status, failReason);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "外呼任务状态更新失败"));
        }

        // 投递 Webhook（外呼任务经第三方应用属主发起，按属主用户关联其全部应用逐个投递状态变更）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("status", status);
        if (StringUtils.hasText(failReason)) {
            payload.put("failReason", failReason);
        }
        List<ApiApp> apps = apiAppMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApiApp>()
                .eq(ApiApp::getUserId, call.getUserId()));
        for (ApiApp app : apps) {
            webhookService.dispatch(WebhookDelivery.EVENT_CALL_STATUS_CHANGED, app.getId(), payload);
        }
        logger.info("PSTN 回调已处理，taskId:{}，status:{}", taskId, status);
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok"));
    }

    private boolean isValidTargetStatus(String status) {
        return OutboundCall.STATUS_ACTIVE.equals(status)
                || OutboundCall.STATUS_COMPLETED.equals(status)
                || OutboundCall.STATUS_FAILED.equals(status);
    }
}