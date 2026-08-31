package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

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

    /** 用于 knowledge_ids JSON 字符串与数组互转 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

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
     * LLM 模型名（如 deepseek-chat、qwen-turbo）
     */
    private String modelName;

    /**
     * 温度（0-2），控制生成随机性
     */
    private Double temperature;

    /**
     * 最大输出 Token 数
     */
    private Integer maxTokens;

    /**
     * 关联知识库ID列表（JSON 数组字符串，如 ["kb_001","kb_002"]）
     * 数据库存储为字符串，JSON 对外表现为数组
     */
    @JsonIgnore
    private String knowledgeIds;

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

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    /** MyBatis-Plus 映射 DB 使用（对外 JSON 隐藏） */
    @JsonIgnore
    public String getKnowledgeIds() {
        return knowledgeIds;
    }

    @JsonIgnore
    public void setKnowledgeIds(String knowledgeIds) {
        this.knowledgeIds = knowledgeIds;
    }

    /** JSON 对外暴露 knowledgeIds 为数组 */
    @JsonProperty("knowledgeIds")
    public List<String> getKnowledgeIdList() {
        if (knowledgeIds == null || knowledgeIds.isBlank()) {
            return List.of();
        }
        try {
            return JSON_MAPPER.readValue(knowledgeIds, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** JSON 反序列化：接收数组，转存为 JSON 字符串（空数组存为 "[]" 以确保可更新） */
    @JsonProperty("knowledgeIds")
    public void setKnowledgeIdList(List<String> ids) {
        try {
            this.knowledgeIds = (ids == null || ids.isEmpty())
                    ? "[]"
                    : JSON_MAPPER.writeValueAsString(ids);
        } catch (Exception e) {
            this.knowledgeIds = "[]";
        }
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