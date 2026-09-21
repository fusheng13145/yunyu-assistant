package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * PSTN 外呼任务实体（P2-17 开放 OpenAPI 语音外呼）
 * 对应数据表：outbound_calls
 * 状态机：PENDING(待发起) → DIALING(呼叫中) → ACTIVE(接通) → COMPLETED(完成)；任一 → FAILED
 * 经可插拔 PstnGateway（HTTP 网关）发起，网关结果通过 /api/open/callbacks/pstn 回调更新
 *
 * @author leyon
 */
@TableName("outbound_calls")
public class OutboundCall {

    /** 状态 - 待发起 */
    public static final String STATUS_PENDING = "PENDING";
    /** 状态 - 呼叫中 */
    public static final String STATUS_DIALING = "DIALING";
    /** 状态 - 已接通 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态 - 已完成 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 状态 - 失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 逻辑删除 - 未删除 */
    public static final int NOT_DELETED = 0;

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 属主用户ID（第三方应用属主）
     */
    private String userId;

    /**
     * 所属组织ID（空=个人数据）
     */
    private String orgId;

    /**
     * 使用的助手ID
     */
    private String assistantId;

    /**
     * 被叫电话号码
     */
    private String phoneNumber;

    /**
     * 状态：PENDING/DIALING/ACTIVE/COMPLETED/FAILED
     */
    private String status;

    /**
     * 失败原因
     */
    private String failReason;

    /**
     * 发起时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getAssistantId() {
        return assistantId;
    }

    public void setAssistantId(String assistantId) {
        this.assistantId = assistantId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }
}