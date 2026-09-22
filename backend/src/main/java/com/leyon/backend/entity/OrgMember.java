package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 组织成员关系实体（P2-10 多租户与商业化前置）
 * 对应数据表：org_members（唯一约束 org_id+user_id，无需逻辑删除）
 * 角色分级：owner(拥有者) > editor(编辑者) > viewer(只读)
 *
 * @author leyon
 */
@TableName("org_members")
public class OrgMember {

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 组织ID
     */
    private String orgId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 角色：owner / editor / viewer
     */
    private String role;

    /**
     * 加入时间
     */
    private LocalDateTime joinedAt;

    /**
     * 成员用户名（非表字段，仅由查询侧批量回填，供前端展示）
     */
    @TableField(exist = false)
    private String username;

    // ========== Getter & Setter ==========
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}