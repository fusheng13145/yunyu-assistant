package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 会话实体
 * 对应数据表：sessions
 * 会话是文本对话的维度容器，替代按助手 ID 拉取"最近 50 条"的临时方案，
 * 支持标题、置顶、删除，为多会话续聊与用量归集打底
 *
 * @author leyon
 */
@TableName("sessions")
public class Session {

    /** 逻辑删除 - 未删除 */
    public static final int NOT_DELETED = 0;
    /** 逻辑删除 - 已删除 */
    public static final int DELETED = 1;

    /** 未置顶 */
    public static final int NOT_PINNED = 0;
    /** 已置顶 */
    public static final int PINNED = 1;

    /** 默认会话标题 */
    public static final String DEFAULT_TITLE = "新对话";

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 归属用户ID
     */
    private String userId;

    /**
     * 关联助手ID
     */
    private String assistantId;

    /**
     * 所属组织ID（P2-10 多租户；空=个人数据，非空=组织数据按角色矩阵）
     */
    private String orgId;

    /**
     * 会话标题（默认"新对话"，首轮对话后自动取用户消息前缀）
     */
    private String title;

    /**
     * 是否置顶
     * 0 = 否，1 = 是
     */
    private Integer isPinned;

    /**
     * 创建时间，插入自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间，插入、更新自动填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标识
     * 0 = 正常，1 = 已删除
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

    public String getAssistantId() {
        return assistantId;
    }

    public void setAssistantId(String assistantId) {
        this.assistantId = assistantId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getIsPinned() {
        return isPinned;
    }

    public void setIsPinned(Integer isPinned) {
        this.isPinned = isPinned;
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