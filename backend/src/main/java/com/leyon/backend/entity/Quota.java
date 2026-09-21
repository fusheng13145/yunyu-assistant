package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 用量配额实体（P2-10 用量配额与账单统计）
 * 对应数据表：quotas（唯一约束 scope_type + scope_id）
 * 作用域：org(组织级，优先) / user(用户级，无组织时兜底)；无记录时用环境变量默认值拼装（不落库）
 *
 * @author leyon
 */
@TableName("quotas")
public class Quota {

    /** 作用域类型 - 组织 */
    public static final String SCOPE_ORG = "org";
    /** 作用域类型 - 用户 */
    public static final String SCOPE_USER = "user";

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 作用域类型：org / user
     */
    private String scopeType;

    /**
     * 作用域ID（org_id 或 user_id）
     */
    private String scopeId;

    /**
     * 助手上限
     */
    private Integer assistantLimit;

    /**
     * 单日通话次数上限
     */
    private Integer dailyCallLimit;

    /**
     * 单日通话时长上限（秒）
     */
    private Long dailyCallSecLimit;

    /**
     * 单日消息量上限
     */
    private Integer dailyMsgLimit;

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

    // ========== Getter & Setter ==========
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    public Integer getAssistantLimit() {
        return assistantLimit;
    }

    public void setAssistantLimit(Integer assistantLimit) {
        this.assistantLimit = assistantLimit;
    }

    public Integer getDailyCallLimit() {
        return dailyCallLimit;
    }

    public void setDailyCallLimit(Integer dailyCallLimit) {
        this.dailyCallLimit = dailyCallLimit;
    }

    public Long getDailyCallSecLimit() {
        return dailyCallSecLimit;
    }

    public void setDailyCallSecLimit(Long dailyCallSecLimit) {
        this.dailyCallSecLimit = dailyCallSecLimit;
    }

    public Integer getDailyMsgLimit() {
        return dailyMsgLimit;
    }

    public void setDailyMsgLimit(Integer dailyMsgLimit) {
        this.dailyMsgLimit = dailyMsgLimit;
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
}