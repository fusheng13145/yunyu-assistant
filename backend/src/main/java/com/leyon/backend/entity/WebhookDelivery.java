package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Webhook 投递记录实体（P2-17 Webhook 回调）
 * 对应数据表：webhook_deliveries
 * 异步投递 + 失败重试（指数退避，最多 app.webhook.retry-max-attempts 次），状态与下次重试时间持久化
 *
 * @author leyon
 */
@TableName("webhook_deliveries")
public class WebhookDelivery {

    /** 状态 - 待投递 */
    public static final String STATUS_PENDING = "PENDING";
    /** 状态 - 投递成功 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 状态 - 投递失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 事件类型 - 通话接通 */
    public static final String EVENT_CALL_CONNECTED = "call.connected";
    /** 事件类型 - 通话结束 */
    public static final String EVENT_CALL_COMPLETED = "call.completed";
    /** 事件类型 - PSTN 外呼状态变更 */
    public static final String EVENT_CALL_STATUS_CHANGED = "call.status_changed";
    /** 事件类型 - 文本对话完成 */
    public static final String EVENT_MESSAGE_COMPLETED = "message.completed";

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 事件类型（call.connected 等）
     */
    private String eventType;

    /**
     * 第三方应用ID
     */
    private String appId;

    /**
     * 事件负载（JSON 字符串）
     */
    private String payload;

    /**
     * 状态：PENDING/SUCCESS/FAILED
     */
    private String status;

    /**
     * 已尝试次数
     */
    private Integer attemptCount;

    /**
     * 下次重试时间（失败且未超次）
     */
    private LocalDateTime nextRetryAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    // ========== Getter & Setter ==========
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}