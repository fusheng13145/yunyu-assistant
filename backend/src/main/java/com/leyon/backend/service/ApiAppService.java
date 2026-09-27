package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.mapper.ApiAppMapper;
import com.leyon.backend.util.ApiKeyHasher;
import com.leyon.backend.util.ExternalUrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
     * 创建应用。
     * <p>
     * API Key 只在返回的实体上带一次明文，落库的是它的 SHA-256（v2.45）；
     * {@code scopes} 是逗号分隔的能力白名单，**不传即只给 chat**（与 v2.45 之前的唯一能力一致），
     * 传了不认识的值则整体拒绝——静默丢弃会让调用方以为拿到了能力，运行时却吃 403。
     *
     * @param webhookUrl 可空；非空时须为公网 http/https（防 SSRF）
     */
    public ApiApp create(String userId, String appName, String webhookUrl, String scopes) {
        if (!StringUtils.hasText(appName) || appName.trim().length() > 64) {
            throw new IllegalArgumentException("应用名称不能为空且不超过64字符");
        }
        validateWebhookUrl(webhookUrl);
        String normalizedScopes = normalizeScopes(scopes);
        ApiApp app = new ApiApp();
        String plainKey = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        // 明文交给调用方一次，库里只留哈希
        app.setAppKey(plainKey);
        app.setAppKeyHash(ApiKeyHasher.hash(plainKey));
        // 回调签名密钥：创建时生成，仅创建响应可见（v2.19 补，v2.17 遗漏导致 Webhook 签名恒不生效）
        app.setWebhookSecret(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        app.setAppName(appName.trim());
        app.setUserId(userId);
        app.setScopes(normalizedScopes);
        app.setWebhookUrl(StringUtils.hasText(webhookUrl) ? webhookUrl.trim() : null);
        app.setEnabled(ApiApp.ENABLED);
        app.setIsDeleted(ApiApp.NOT_DELETED);
        apiAppMapper.insert(app);
        return app;
    }

    /**
     * 能力白名单归一：按 {@link ApiApp#ALL_SCOPES} 的固定顺序重排、去重，未知值拒绝。
     * 空输入回默认 {@link ApiApp#SCOPE_CHAT}——但**不会**回"全部能力"。
     */
    private String normalizeScopes(String scopes) {
        if (!StringUtils.hasText(scopes)) {
            return ApiApp.SCOPE_CHAT;
        }
        List<String> requested = new ArrayList<>();
        for (String raw : scopes.split(",")) {
            String scope = raw.trim().toLowerCase();
            if (scope.isEmpty()) {
                continue;
            }
            if (!ApiApp.ALL_SCOPES.contains(scope)) {
                throw new IllegalArgumentException("未知的能力: " + scope + "（可选：" + String.join("/", ApiApp.ALL_SCOPES) + "）");
            }
            if (!requested.contains(scope)) {
                requested.add(scope);
            }
        }
        if (requested.isEmpty()) {
            return ApiApp.SCOPE_CHAT;
        }
        // 按白名单顺序输出，保证同一组能力的存储形态唯一（便于比对与台账阅读）
        List<String> ordered = new ArrayList<>(ApiApp.ALL_SCOPES);
        ordered.retainAll(requested);
        return String.join(",", ordered);
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
     * 按 API Key 鉴权：有效且启用返回应用，否则 null。
     * <p>
     * 自 v2.45 起**按哈希等值查库**（{@code uk_app_key_hash} 仍是索引命中，查询成本不变），
     * 因此"比对是否常量时间"这个问题在这里已经消失了：能撞上的只有 SHA-256 的原像，
     * 而索引查找不逐字符比较密钥。返回的实体不含明文 Key（该列已不存在）。
     */
    public ApiApp authByApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        ApiApp app = apiAppMapper.selectOne(new LambdaQueryWrapper<ApiApp>()
                .eq(ApiApp::getAppKeyHash, ApiKeyHasher.hash(apiKey))
                .last("LIMIT 1"));
        if (app == null || app.getEnabled() == null || app.getEnabled() != ApiApp.ENABLED) {
            return null;
        }
        return app;
    }
}