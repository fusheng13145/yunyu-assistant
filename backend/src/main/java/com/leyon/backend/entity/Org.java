package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 组织实体（P2-10 多租户与商业化前置）
 * 对应数据表：orgs
 * 组织为数据隔离与配额聚合的可选增强层；资源 org_id 为空时仍按 user_id 个人隔离
 *
 * @author leyon
 */
@TableName("orgs")
public class Org {

    /** 逻辑删除 - 未删除 */
    public static final int NOT_DELETED = 0;
    /** 逻辑删除 - 已删除 */
    public static final int DELETED = 1;

    /** 成员角色 - 拥有者 */
    public static final String ROLE_OWNER = "owner";
    /** 成员角色 - 编辑者 */
    public static final String ROLE_EDITOR = "editor";
    /** 成员角色 - 只读 */
    public static final String ROLE_VIEWER = "viewer";

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 组织名
     */
    private String name;

    /**
     * 创建者(owner)用户ID
     */
    private String ownerUserId;

    /**
     * 组织描述
     */
    private String description;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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