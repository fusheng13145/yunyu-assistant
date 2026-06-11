package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/**
 * 聊天记录实体
 * 对应数据表: records
 */
@TableName("records")
public class Record {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属助手ID */
    private String assistantId;

    /**
     * 角色: 0=用户(user), 1=助手(assistant)
     */
    private Integer role;

    /** 消息内容 */
    private String message;

    /** AI响应耗时(毫秒)，仅assistant消息有值 */
    private Long costTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Integer isDeleted;

    public Record() {}

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAssistantId() { return assistantId; }
    public void setAssistantId(String assistantId) { this.assistantId = assistantId; }
    public Integer getRole() { return role; }
    public void setRole(Integer role) { this.role = role; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getCostTime() { return costTime; }
    public void setCostTime(Long costTime) { this.costTime = costTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }

    /** 角色常量 */
    public static final int ROLE_USER = 0;
    public static final int ROLE_ASSISTANT = 1;
}
