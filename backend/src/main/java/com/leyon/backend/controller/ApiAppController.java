package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 开放平台应用管理接口（P2-10 开放 OpenAPI）
 * 用户创建/查看/吊销自己的第三方应用 API Key（走 JWT 鉴权的管理侧；第三方调用走 /api/open/** 的 X-API-Key）
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/openapi")
public class ApiAppController {

    private final ApiAppService apiAppService;

    public ApiAppController(ApiAppService apiAppService) {
        this.apiAppService = apiAppService;
    }

    /**
     * 创建第三方应用，返回 app_key（仅展示一次）；可指定 webhookUrl（P2-17）与 scopes（v2.45）
     */
    @Audit(action = "API_APP_CREATE", targetType = "api_app")
    @PostMapping("/apps")
    public ApiResponse<ApiApp> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String appName = body == null ? null : body.get("appName");
        if (!StringUtils.hasText(appName)) {
            return ApiResponse.paramError("应用名称不能为空");
        }
        String webhookUrl = body == null ? null : body.get("webhookUrl");
        String scopes = body == null ? null : body.get("scopes");
        try {
            ApiApp app = apiAppService.create(userId, appName, webhookUrl, scopes);
            return ApiResponse.success(app);
        } catch (IllegalArgumentException e) {
            return ApiResponse.paramError(e.getMessage());
        }
    }

    /**
     * 查询我的应用列表（隐藏 app_key 与 webhook_secret，展示 webhookUrl）
     */
    @GetMapping("/apps")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        List<ApiApp> apps = apiAppService.listByUser(userId);
        List<Map<String, Object>> result = apps.stream().map(app -> {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("id", app.getId());
            item.put("appName", app.getAppName());
            item.put("scope", app.getScopes());
            item.put("webhookUrl", app.getWebhookUrl());
            item.put("enabled", app.getEnabled());
            item.put("createdAt", app.getCreatedAt());
            return item;
        }).toList();
        return ApiResponse.success(result);
    }

    /**
     * 变更应用能力（v2.46 · 候选 ㉗）：整串替换，下一个请求即生效。
     * <p>
     * 之所以必须有这个口：自 v2.45 起明文 Key 不可回读，"吊销后重建"不再是一条可行的调整路径
     * ——它等于逼调用方换 Key。审计在此记 old→new（{@code auditDetail} 由 {@code AuditAspect} 落进
     * {@code audit_logs.detail}），因为"谁把外呼能力放开了"与"放开了什么"是两回事。
     */
    @Audit(action = "API_APP_SCOPES_UPDATE", targetType = "api_app")
    @PutMapping("/apps/{id}/scopes")
    public ApiResponse<Map<String, String>> updateScopes(@PathVariable String id,
                                                         @RequestBody Map<String, String> body,
                                                         HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String scopes = body == null ? null : body.get("scopes");
        try {
            ApiAppService.ScopeChange change = apiAppService.updateScopes(id, userId, scopes);
            if (change == null) {
                return ApiResponse.paramError("应用不存在或无操作权限");
            }
            request.setAttribute("auditDetail", Map.of("from", change.previousScopes(), "to", change.scopes()));
            return ApiResponse.success(Map.of("id", change.appId(), "scopes", change.scopes()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.paramError(e.getMessage());
        }
    }

    /**
     * 吊销应用（逻辑删除；第三方 API Key 即刻失效）
     */
    @Audit(action = "API_APP_REVOKE", targetType = "api_app")
    @DeleteMapping("/apps/{id}")
    public ApiResponse<Void> revoke(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        boolean revoked = apiAppService.revoke(id, userId);
        if (!revoked) {
            return ApiResponse.paramError("应用不存在或无操作权限");
        }
        return ApiResponse.success();
    }
}