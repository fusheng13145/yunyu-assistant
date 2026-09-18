package com.leyon.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据归档结果（P2-9）
 * 记录一次归档执行的三表归档条数与录音文件清理统计。
 *
 * @author leyon
 */
public class ArchiveResult {

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** records 归档条数 */
    private int recordsArchived;

    /** call_records 归档条数 */
    private int callRecordsArchived;

    /** audit_logs 归档条数 */
    private int auditLogsArchived;

    /** 成功删除的录音文件数 */
    private int recordingsDeleted;

    /** 删除失败的录音文件数（缺失/IO 异常，不影响数据归档） */
    private int recordingsFailed;

    /** 本次归档过程中收集的待删录音文件名（内部使用，删除后清空） */
    private final List<String> recordingNames = new ArrayList<>();

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public int getRecordsArchived() {
        return recordsArchived;
    }

    public void setRecordsArchived(int recordsArchived) {
        this.recordsArchived = recordsArchived;
    }

    public int getCallRecordsArchived() {
        return callRecordsArchived;
    }

    public void setCallRecordsArchived(int callRecordsArchived) {
        this.callRecordsArchived = callRecordsArchived;
    }

    public int getAuditLogsArchived() {
        return auditLogsArchived;
    }

    public void setAuditLogsArchived(int auditLogsArchived) {
        this.auditLogsArchived = auditLogsArchived;
    }

    public int getRecordingsDeleted() {
        return recordingsDeleted;
    }

    public void setRecordingsDeleted(int recordingsDeleted) {
        this.recordingsDeleted = recordingsDeleted;
    }

    public int getRecordingsFailed() {
        return recordingsFailed;
    }

    public void setRecordingsFailed(int recordingsFailed) {
        this.recordingsFailed = recordingsFailed;
    }

    public void addRecordingNames(List<String> names) {
        this.recordingNames.addAll(names);
    }

    public List<String> getRecordingNames() {
        return recordingNames;
    }
}