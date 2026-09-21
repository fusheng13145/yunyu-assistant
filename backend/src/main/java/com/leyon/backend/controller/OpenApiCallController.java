package com.leyon.backend.controller;

import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.WebhookDelivery;
import com.leyon.backend.interceptor.OpenApiAuthInterceptor;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.OrgService;
import com.leyon.backend.service.OutboundCallService;
import com.leyon.backend.service.QuotaService;
import com.leyon.backend.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放 OpenAPI 语音外呼接口（P2-17）
 * 经 /api/open/** 路由，由 OpenApiAuthInterceptor 以 X-API-Key 鉴权
 * POST /api/open/call：第三方发起 PSTN 外呼 → 创建外呼任务（PENDING）后异步经可插拔 PstnGateway 发起，
 * 返回 {taskId, status:"PENDING"}；呼叫后续状态经 /api/open/callbacks/pstn 网关回调更新并触发 Webhook。
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/open")
public class OpenApiCallController {

    private final AssistantService assistantService;
    private final OrgService orgService;
    private final QuotaService quotaService;
    private final OutboundCallService outboundCallService;
    private final WebhookService webhookService;

    public OpenApiCallController(AssistantService assistantService,
                                 OrgService orgService,
                                 QuotaService quotaService,
                                 OutboundCallService outboundCallService,
                                 WebhookService webhookService) {
        this.assistantService = assistantService;
        this.orgService = orgService;
        this.quotaService = quotaService;
        this.outboundCallService = outboundCallService;
        this.webhookService = webhookService;
    }

    /**
     * PSTN 外呼：校验通过后创建外呼任务并异步发起，返回 {taskId, status}
     * body: { assistantId, phoneNumber }
     */
    @PostMapping("/call")
    public ResponseEntity<Map<String, Object>> call(@RequestBody Map<String, String> body,
                                                    HttpServletRequest request) {
        String assistantId = body == null ? null : body.get("assistantId");
        String phoneNumber = body == null ? null : body.get("phoneNumber");
        String userId = (String) request.getAttribute(OpenApiAuthInterceptor.ATTR_USER_ID);
        String appId = (String) request.getAttribute(OpenApiAuthInterceptor.ATTR_APP_ID);

        // 参数校验
        if (!StringUtils.hasText(assistantId)) {
            return badRequest("assistantId 不能为空");
        }
        if (!StringUtils.hasText(phoneNumber)) {
            return badRequest("phoneNumber 不能为空");
        }

        // 助手归属校验（个人数据按 userId；组织数据按成员 viewer 以上可读/使用）
        Assistant assistant = assistantService.getById(assistantId);
        if (assistant == null) {
            return badRequest("助手不存在");
        }
        try {
            requireRead(assistant, userId);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", 403, "message", e.getMessage()));
        }

        // 配额拦截（通话次数/时长，计入第三方属主身份）
        try {
            quotaService.checkStartCall(userId);
        } catch (QuotaExceededException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", 403, "message", e.getMessage()));
        }

        // 创建外呼任务（PENDING）并异步发起
        String taskId = outboundCallService.create(userId, assistant.getOrgId(), assistantId, phoneNumber);
        outboundCallService.initiateAsync(taskId);
        dispatchStatusChanged(appId, taskId, "PENDING", null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", "PENDING");
        result.put("message", "外呼任务已创建，正在经网关发起");
        return ResponseEntity.ok(result);
    }

    private void dispatchStatusChanged(String appId, String taskId, String status, String failReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("status", status);
        if (StringUtils.hasText(failReason)) {
            payload.put("failReason", failReason);
        }
        webhookService.dispatch(WebhookDelivery.EVENT_CALL_STATUS_CHANGED, appId, payload);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", message));
    }

    /**
     * 读取校验：个人资源按 userId；组织资源需为组织成员（viewer 以上）
     * 与 OpenApiChatController 语义一致
     */
    private void requireRead(Assistant assistant, String userId) {
        if (StringUtils.hasText(assistant.getOrgId())) {
            if (!orgService.isMember(assistant.getOrgId(), userId)) {
                throw new ForbiddenException("无权访问该助手");
            }
        } else if (!userId.equals(assistant.getUserId())) {
            throw new ForbiddenException("无权访问该助手");
        }
    }
}