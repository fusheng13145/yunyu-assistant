package com.leyon.backend.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据归档配置（app.archive.*，P2-9）
 *
 * @author leyon
 */
@Component
@ConfigurationProperties(prefix = "app.archive")
public class ArchiveProperties {

    /** 定时归档开关（默认关闭，管理端可手动触发） */
    private boolean scheduleEnabled = false;

    /** 定时 cron 表达式（Spring 6 字段格式，默认每日凌晨 3 点） */
    private String cron = "0 0 3 * * ?";

    /** 每批次处理条数（含 TEXT/JSON 大字段） */
    private int batchSize = 500;

    /** records 保留天数（超期即归档） */
    private int recordsRetentionDays = 180;

    /** call_records 保留天数（超期即归档，录音文件随行清理） */
    private int callRecordsRetentionDays = 180;

    /** audit_logs 保留天数（超期即归档） */
    private int auditLogsRetentionDays = 180;

    /** 分布式锁 TTL（毫秒，v2.19 跨实例防重；默认 10 分钟） */
    private long lockTtlMs = 600_000;

    public boolean isScheduleEnabled() {
        return scheduleEnabled;
    }

    public void setScheduleEnabled(boolean scheduleEnabled) {
        this.scheduleEnabled = scheduleEnabled;
    }

    public long getLockTtlMs() {
        return lockTtlMs;
    }

    public void setLockTtlMs(long lockTtlMs) {
        this.lockTtlMs = lockTtlMs;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getRecordsRetentionDays() {
        return recordsRetentionDays;
    }

    public void setRecordsRetentionDays(int recordsRetentionDays) {
        this.recordsRetentionDays = recordsRetentionDays;
    }

    public int getCallRecordsRetentionDays() {
        return callRecordsRetentionDays;
    }

    public void setCallRecordsRetentionDays(int callRecordsRetentionDays) {
        this.callRecordsRetentionDays = callRecordsRetentionDays;
    }

    public int getAuditLogsRetentionDays() {
        return auditLogsRetentionDays;
    }

    public void setAuditLogsRetentionDays(int auditLogsRetentionDays) {
        this.auditLogsRetentionDays = auditLogsRetentionDays;
    }
}