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
     * 能力白名单归一（创建侧）：空输入回默认 {@link ApiApp#SCOPE_CHAT}——但**不会**回"全部能力"。
     * <p>
     * 与变更侧 {@link #updateScopes} 对"空"的判定刻意相反，见那边的说明。
     */
    private String normalizeScopes(String scopes) {
        List<String> parsed = parseScopes(scopes);
        return joinOrdered(parsed.isEmpty() ? List.of(ApiApp.SCOPE_CHAT) : parsed);
    }

    /**
     * 能力串的解析、校验与去重（创建与变更**共用**）：不认识的值直接抛，不静默丢弃——
     * 静默丢弃会让调用方以为授予了能力，运行时却吃 403。
     *
     * @return 去重后的能力集合，可能为空（调用方决定空集怎么处理）
     */
    private List<String> parseScopes(String scopes) {
        List<String> requested = new ArrayList<>();
        if (StringUtils.hasText(scopes)) {
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
        }
        return requested;
    }

    /** 按白名单顺序输出，保证同一组能力的存储形态唯一（便于比对、台账阅读与断言） */
    private String joinOrdered(List<String> scopes) {
        List<String> ordered = new ArrayList<>(ApiApp.ALL_SCOPES);
        ordered.retainAll(scopes);
        return String.join(",", ordered);
    }

    /**
     * 变更应用能力（v2.46 · 候选 ㉗）。整串替换，不做增量授予——"只加不减"的接口
     * 会让调用方无法表达"停掉外呼"这个真实诉求，而停掉恰恰是这套能力白名单存在的理由。
     * <p>
     * 判定每请求查库、无任何缓存，所以改完**下一个请求即生效**；唯一不受此约束的是
     * 已建立的开放语音会话（握手时一次性判定），口径见手册 6.6。
     * <p>
     * 空集在这里是**拒绝**而不是"清空"，与创建侧"空→默认 chat"正好相反：
     * 两处若共用同一个归一函数，"取消全部勾选"就会变成"只保留 chat"——那是给用户一个
     * 他们没点过的能力。要停掉全部能力请吊销应用（吊销是终态）。
     *
     * @return 变更前后两值（供审计与回显）；应用不存在、已吊销或不属于该用户时返回 null
     * @throws IllegalArgumentException 能力串含未知值或为空集（此时库里原值不变）
     */
    public ScopeChange updateScopes(String id, String userId, String scopes) {
        if (!StringUtils.hasText(id) || !StringUtils.hasText(userId)) {
            return null;
        }
        // 先校验入参再查库：非法能力串不该产生任何读表与写表动作
        List<String> requested = parseScopes(scopes);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("至少要保留一项能力；要停掉全部能力请吊销应用（吊销后 API Key 即刻失效且不可恢复）");
        }
        ApiApp app = apiAppMapper.selectById(id);
        if (app == null || !userId.equals(app.getUserId())) {
            return null;
        }
        String normalized = joinOrdered(requested);
        // 只带 id 与 scopes：MyBatis-Plus 默认跳过 null 字段，凭据列因此不可能被整行覆盖抹掉
        ApiApp update = new ApiApp();
        update.setId(id);
        update.setScopes(normalized);
        if (apiAppMapper.updateById(update) == 0) {
            return null;
        }
        // 列本身 NOT NULL，这里兜的是"有人手工把库改成 NULL"：改后值已落库，
        // 若因旧值取不出来抛 NPE，客户端会看到 500 而实际改动已生效——那比读成一个空串糟得多。
        return new ScopeChange(id, java.util.Objects.toString(app.getScopes(), ""), normalized);
    }

    /**
     * 能力变更的结果（改前值 + 改后值）。改前值必须由服务一起交出来：审计要写 old→new，
     * 而控制器拿不到它就得再查一次库——两次读之间值可能已变，那就不叫"改前"了。
     */
    public record ScopeChange(String appId, String previousScopes, String scopes) {
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

    /**
     * 长连接场景的资格复核：应用**当前**是否仍启用且带某能力。
     * <p>
     * 与 {@link #authByApiKey} 的区别只在入口——握手已用 Key 证明过身份，这里按会话里已注入的 appId 回查，
     * 因此不存在"谁提供了凭据"的问题，只回答"这份资格还在不在"。判定**每次查库、不缓存**：
     * 缓存等于把"改能力后 N 秒仍可用"写回来（与 v2.42 令牌版本戳同一口径）。
     * 吊销走逻辑删除 ⇒ {@code selectById} 读不到行 ⇒ 一律拒绝，无需另判。
     */
    public boolean accessGranted(String appId, String scope) {
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(scope)) {
            return false;
        }
        ApiApp app = apiAppMapper.selectById(appId);
        return app != null && app.getEnabled() != null && app.getEnabled() == ApiApp.ENABLED
                && app.hasScope(scope);
    }
}