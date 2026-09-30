package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

/**
 * 第三方应用实体（P2-10 开放 OpenAPI）
 * 对应数据表：api_apps
 * <p>
 * API Key 自 v2.45 起**只存哈希**（{@code app_key_hash}，SHA-256 hex）：明文只在创建响应里一次性回显，
 * 落库与鉴权都不再持有它，故库备份外泄不再等于把所有第三方凭据一起交出去。字段 {@link #appKey}
 * 因此标成 {@code @TableField(exist = false)}——它不是列，只是"创建那一次"的载体。
 * <p>
 * {@code scopes} 是逗号分隔的能力白名单，自 v2.45 起**真正参与判定**（此前全仓无读判点，
 * 等于每个有效 key 天然拥有全部能力），取值见 {@link #SCOPE_CHAT} 等常量与两个 OpenAPI 拦截器。
 *
 * @author leyon
 */
@TableName("api_apps")
public class ApiApp {

    /** 逻辑删除 - 未删除 */
    public static final int NOT_DELETED = 0;
    /** 逻辑删除 - 已删除 */
    public static final int DELETED = 1;

    /** 启用 */
    public static final int ENABLED = 1;
    /** 停用 */
    public static final int DISABLED = 0;

    /** 能力范围 - 文本对话（POST /api/open/chat） */
    public static final String SCOPE_CHAT = "chat";
    /** 能力范围 - 电话外呼（POST /api/open/call） */
    public static final String SCOPE_CALL = "call";
    /** 能力范围 - 语音会话（WS /api/open/ws-voice/*） */
    public static final String SCOPE_VOICE = "voice";

    /**
     * 全部合法能力，顺序即前端勾选顺序。创建入参按此白名单过滤，不认识的值直接拒绝而不是静默丢弃——
     * 静默丢弃会让调用方以为授予了能力却在运行时收到 403。
     */
    public static final java.util.List<String> ALL_SCOPES = java.util.List.of(SCOPE_CHAT, SCOPE_CALL, SCOPE_VOICE);

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * API Key 的 SHA-256 hex（唯一；鉴权时按 {@code X-API-Key} 现算哈希后等值查库）
     */
    private String appKeyHash;

    /**
     * 明文 API Key——**非数据库列**，只在创建那一次的显式载荷里出现一次，之后任何路径都取不到。
     * 标 {@code exist=false} 是刻意的：一旦它还能被持久化，"哈希落库"就只是多存一列。
     */
    @TableField(exist = false)
    private String appKey;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 属主用户ID（第三方用量计入其配额）
     */
    private String userId;

    /**
     * 能力范围，逗号分隔（chat / call / voice）
     */
    private String scopes;

    /**
     * Webhook 回调地址（P2-17；空=不接收事件回调）
     */
    private String webhookUrl;

    /**
     * Webhook 签名密钥（P2-17；用于 HMAC-SHA256 签名，空=不签名）
     * <p>
     * 它是**真实列**，所以外发抑制必须落在实体上而不是落在某个出口的白名单上：接收方要拿它验签，
     * 但唯一该拿到它的时刻是创建那一次（v2.17 漏了生成，签名恒不生效；v2.19 补的就是这一次可见）。
     */
    private String webhookSecret;

    /**
     * 是否启用 1:启用 0:停用
     */
    private Integer enabled;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标识
     */
    @TableLogic
    private Integer isDeleted;

    // ========== Getter & Setter ==========
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    /** 哈希不外发：创建响应只需要给一次明文，多带一列不增加任何可用性（v2.45） */
    @JsonIgnore
    public String getAppKeyHash() {
        return appKeyHash;
    }

    public void setAppKeyHash(String appKeyHash) {
        this.appKeyHash = appKeyHash;
    }

    /**
     * 明文 Key 也不走实体序列化（v2.64）：它只在 {@code POST /api/openapi/apps} 那一次由出口显式放进载荷。
     * 区别在于"谁能决定它出去"——留在实体上时，任何"顺手把整个实体放进响应"的新端点都会把它再发一遍。
     */
    @JsonIgnore
    public String getAppKey() {
        return appKey;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /**
     * 抑制出站（v2.64）：列表接口今天恰好手写了字段白名单，所以这一列看起来"没外发"，
     * 但那份白名单管不到未来的任何一个 {@code return ApiResponse.success(app)}。
     */
    @JsonIgnore
    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @JsonIgnore
    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }

    /**
     * 是否具备某项能力（判定单点，避免两个拦截器各写一份逗号串解析）。
     * scopes 为空一律判 false——**没有"空=全部放行"的兜底**：能力白名单一旦允许空集通配，
     * 漏配 scopes 就等于授予全部能力，与 v2.45 要收口的正是同一件事。
     */
    public boolean hasScope(String scope) {
        if (scopes == null || scopes.isBlank() || scope == null) {
            return false;
        }
        for (String granted : scopes.split(",")) {
            if (granted.trim().equals(scope)) {
                return true;
            }
        }
        return false;
    }
}