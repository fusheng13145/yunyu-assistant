package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单日配额用量实体（v2.35 配额原子扣减）
 * 对应数据表：quota_daily_usage（唯一约束 scope_type + scope_id + usage_date + metric）
 * <p>
 * 本表是配额的<b>判定账本</b>：{@code used} 只由带 {@code used < limit} 条件的原子 UPDATE 推进，
 * 与 {@code QuotaService.aggregateUsage} 从业务表（call_records / records）数出来的展示口径
 * 允许漂移——业务记录可能回滚、删除或归档，而额度扣减刻意<b>不退还</b>（宁少放行不透支）。
 *
 * @author leyon
 */
@TableName("quota_daily_usage")
public class QuotaDailyUsage {

    /** 指标 - 单日消息量（文本对话每条用户消息扣 1） */
    public static final String METRIC_DAILY_MSG = "daily_msg";
    /** 指标 - 单日通话次数（发起通话扣 1；通话时长按业务表只读判定，不建指标） */
    public static final String METRIC_DAILY_CALL = "daily_call";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 作用域类型：org / user（与 quotas 同口径） */
    private String scopeType;

    /** 作用域ID（org_id 或 user_id） */
    private String scopeId;

    /** 统计日 */
    private LocalDate usageDate;

    /** 指标名：daily_msg / daily_call */
    private String metric;

    /** 当日已用量（仅由原子扣减推进） */
    private Integer used;

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

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public void setUsageDate(LocalDate usageDate) {
        this.usageDate = usageDate;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public Integer getUsed() {
        return used;
    }

    public void setUsed(Integer used) {
        this.used = used;
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
