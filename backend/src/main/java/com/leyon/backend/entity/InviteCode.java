package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 注册邀请码实体（v2.37 邀请码制注册）
 * 对应数据表：invite_code（唯一约束 code）
 * <p>
 * 一码一用：领取只由带 {@code used_by IS NULL} 条件的原子 UPDATE 推进（见
 * {@code InviteCodeMapper.claimByCode}）。{@code used_by} 在注册事务里写入的正是新建账号的
 * id，因此"这个码被谁用掉了"可回溯；账号被逻辑删除后此处仍留痕，与配额账本同一口径（不回收、不退还）。
 *
 * @author leyon
 */
@TableName("invite_code")
public class InviteCode {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 邀请码本体（大写字母与数字，排除易混字符） */
    private String code;

    /** 生成者（管理员用户ID） */
    private String createdBy;

    /** 使用者（注册成功的用户ID），null=未使用 */
    private String usedBy;

    /** 使用时间，null=未使用 */
    private LocalDateTime usedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUsedBy() {
        return usedBy;
    }

    public void setUsedBy(String usedBy) {
        this.usedBy = usedBy;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
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
