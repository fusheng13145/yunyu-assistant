package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 第三方应用实体（P2-10 开放 OpenAPI）
 * 对应数据表：api_apps
 * app_key 为明文 API Key（第三方请求头 X-API-Key 携带），scopes 逗号分隔能力（chat:文本对话）
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

    /** 能力范围 - 文本对话 */
    public static final String SCOPE_CHAT = "chat";

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * API Key（明文，唯一；第三方请求头 X-API-Key 携带）
     */
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
     * 能力范围，逗号分隔（chat）
     */
    private String scopes;

    /**
     * Webhook 回调地址（P2-17；空=不接收事件回调）
     */
    private String webhookUrl;

    /**
     * Webhook 签名密钥（P2-17；用于 HMAC-SHA256 签名，空=不签名）
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

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
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

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }
}