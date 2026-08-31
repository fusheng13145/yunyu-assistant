package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 操作审计日志实体
 * 对应数据表：audit_logs
 *
 * @author leyon
 */
@TableName("audit_logs")
public class AuditLog {

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 操作人用户ID（未登录时为 null）
     */
    private String userId;

    /**
     * 操作动作（LOGIN / ASSISTANT_CREATE / ASSISTANT_UPDATE / ASSISTANT_DELETE / KB_CREATE / KB_DELETE 等）
     */
    private String action;

    /**
     * 目标类型
     */
    private String targetType;

    /**
     * 目标ID
     */
    private String targetId;

    /**
     * 详情（JSON 字符串）
     */
    private String detail;

    /**
     * 客户端 IP
     */
    private String ip;

    /**
     * 操作结果：1 成功 / 0 失败
     */
    private Integer result;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getResult() {
        return result;
    }

    public void setResult(Integer result) {
        this.result = result;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
