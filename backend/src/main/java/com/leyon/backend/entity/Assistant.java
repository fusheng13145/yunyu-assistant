package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 助手信息实体
 * 对应数据表：assistants
 *
 * @author leyon
 */
@TableName("assistants")
public class Assistant {

    /** 逻辑删除-未删除 */
    public static final int NOT_DELETED = 0;
    /** 逻辑删除-已删除 */
    public static final int DELETED = 1;

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 助手名称
     */
    private String name;

    /**
     * 助手简介描述
     */
    private String description;

    /**
     * AI 人设 / 系统提示词
     */
    private String personality;

    /**
     * TTS 语音音色编码
     */
    private String voice;

    /**
     * 归属用户ID
     */
    private String userId;

    /**
     * 创建时间，自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 最后更新时间，自动填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标识
     * 0 = 正常，1 = 已删除
     */
    @TableLogic
    private Integer isDeleted;

    public Assistant() {
    }

    public Assistant(String id, String name, String description, String personality, String voice,
                     String userId, LocalDateTime createdAt, LocalDateTime updatedAt, Integer isDeleted) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.personality = personality;
        this.voice = voice;
        this.userId = userId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.isDeleted = isDeleted;
    }

    // ========== Getter & Setter ==========
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
    }

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    @Override
    public String toString() {
        return "Assistant{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", personality='" + personality + '\'' +
                ", voice='" + voice + '\'' +
                ", userId='" + userId + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", isDeleted=" + isDeleted +
                '}';
    }
}