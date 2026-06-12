package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 聊天记录实体
 * 对应数据表：records
 *
 * @author leyon
 */
@TableName("records")
public class Record {

    // 角色常量
    /** 角色-用户 */
    public static final int ROLE_USER = 0;
    /** 角色-助手 */
    public static final int ROLE_ASSISTANT = 1;

    // 逻辑删除常量
    /** 未删除 */
    public static final int NOT_DELETED = 0;
    /** 已删除 */
    public static final int DELETED = 1;

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 所属助手ID
     */
    private String assistantId;

    /**
     * 消息角色
     * 0 = 用户消息，1 = 助手回复消息
     */
    private Integer role;

    /**
     * 聊天消息内容
     */
    private String message;

    /**
     * AI 响应耗时(毫秒)，仅助手消息记录该字段
     */
    private Long costTime;

    /**
     * 创建时间，插入自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 逻辑删除标识
     * 0 = 正常，1 = 已删除
     */
    @TableLogic
    private Integer isDeleted;

    public Record() {
    }

    public Record(String id, String assistantId, Integer role, String message,
                  Long costTime, LocalDateTime createdAt, Integer isDeleted) {
        this.id = id;
        this.assistantId = assistantId;
        this.role = role;
        this.message = message;
        this.costTime = costTime;
        this.createdAt = createdAt;
        this.isDeleted = isDeleted;
    }

    // ========== Getter & Setter ==========
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAssistantId() {
        return assistantId;
    }

    public void setAssistantId(String assistantId) {
        this.assistantId = assistantId;
    }

    public Integer getRole() {
        return role;
    }

    public void setRole(Integer role) {
        this.role = role;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getCostTime() {
        return costTime;
    }

    public void setCostTime(Long costTime) {
        this.costTime = costTime;
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

    @Override
    public String toString() {
        return "Record{" +
                "id='" + id + '\'' +
                ", assistantId='" + assistantId + '\'' +
                ", role=" + role +
                ", message='" + message + '\'' +
                ", costTime=" + costTime +
                ", createdAt=" + createdAt +
                ", isDeleted=" + isDeleted +
                '}';
    }
}