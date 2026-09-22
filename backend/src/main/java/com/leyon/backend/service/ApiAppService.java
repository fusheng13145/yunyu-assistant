package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.mapper.ApiAppMapper;
import com.leyon.backend.util.ExternalUrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * 第三方应用服务（P2-10 开放 OpenAPI）
 * 提供应用的创建/列表/吊销与 API Key 鉴权查询
 *
 * @author leyon
 */
@Service
public class ApiAppService {

    private final ApiAppMapper apiAppMapper;

    public ApiAppService(ApiAppMapper apiAppMapper) {
        this.apiAppMapper = apiAppMapper;
    }

    /**
     * 创建应用，生成明文 app_key（UUID），scopes 默认 chat；可指定 webhookUrl（P2-17）
     */
    public ApiApp create(String userId, String appName, String webhookUrl) {
        if (!StringUtils.hasText(appName) || appName.trim().length() > 64) {
            throw new IllegalArgumentException("应用名称不能为空且不超过64字符");
        }
        validateWebhookUrl(webhookUrl);
        ApiApp app = new ApiApp();
        app.setAppKey(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        // 回调签名密钥：创建时生成，仅创建响应可见（v2.19 补，v2.17 遗漏导致 Webhook 签名恒不生效）
        app.setWebhookSecret(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        app.setAppName(appName.trim());
        app.setUserId(userId);
        app.setScopes(ApiApp.SCOPE_CHAT);
        app.setWebhookUrl(StringUtils.hasText(webhookUrl) ? webhookUrl.trim() : null);
        app.setEnabled(ApiApp.ENABLED);
        app.setIsDeleted(ApiApp.NOT_DELETED);
        apiAppMapper.insert(app);
        return app;
    }

    /**
     * Webhook 地址校验：非空时须为可安全访问的公网 http/https 地址（防 SSRF）
     */
    private void validateWebhookUrl(String webhookUrl) {
        if (!StringUtils.hasText(webhookUrl)) {
            return;
        }
        try {
            ExternalUrlValidator.requirePublicHttpUrl(webhookUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Webhook " + e.getMessage());
        }
    }

    /**
     * 查询某用户的应用列表（按创建时间倒序）
     */
    public List<ApiApp> listByUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        return apiAppMapper.selectList(new LambdaQueryWrapper<ApiApp>()
                .eq(ApiApp::getUserId, userId)
                .orderByDesc(ApiApp::getCreatedAt));
    }

    /**
     * 吊销应用（属主校验，逻辑删除）
     */
    public boolean revoke(String id, String userId) {
        if (!StringUtils.hasText(id)) {
            return false;
        }
        ApiApp app = apiAppMapper.selectById(id);
        if (app == null || !userId.equals(app.getUserId())) {
            return false;
        }
        return apiAppMapper.deleteById(id) > 0;
    }

    /**
     * 按 API Key 鉴权：有效且启用返回应用，否则 null
     */
    public ApiApp authByApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        ApiApp app = apiAppMapper.selectOne(new LambdaQueryWrapper<ApiApp>()
                .eq(ApiApp::getAppKey, apiKey)
                .last("LIMIT 1"));
        if (app == null || app.getEnabled() == null || app.getEnabled() != ApiApp.ENABLED) {
            return null;
        }
        return app;
    }
}