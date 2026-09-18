package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Record;
import com.leyon.backend.mapper.ArchiveMapper;
import com.leyon.backend.mapper.AuditLogMapper;
import com.leyon.backend.mapper.CallRecordMapper;
import com.leyon.backend.mapper.RecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据归档与容量治理服务（P2-9）
 * 将超期（created_at < 保留期）的 records / call_records / audit_logs 复制到
 * 对应 *_archive 归档表（保留原 id）后物理删除源表，并清理 call_records 关联的录音文件。
 * 幂等：已超期的行本次全部处理；DB 主循环失败整体回滚，重跑不产生重复归档。
 *
 * @author leyon
 */
@Service
public class DataArchiveService {

    private static final Logger logger = LoggerFactory.getLogger(DataArchiveService.class);

    private final RecordMapper recordMapper;
    private final CallRecordMapper callRecordMapper;
    private final AuditLogMapper auditLogMapper;
    private final ArchiveMapper archiveMapper;
    private final RecordingFileStore recordingFileStore;
    private final ArchiveProperties props;

    public DataArchiveService(RecordMapper recordMapper,
                              CallRecordMapper callRecordMapper,
                              AuditLogMapper auditLogMapper,
                              ArchiveMapper archiveMapper,
                              RecordingFileStore recordingFileStore,
                              ArchiveProperties props) {
        this.recordMapper = recordMapper;
        this.callRecordMapper = callRecordMapper;
        this.auditLogMapper = auditLogMapper;
        this.archiveMapper = archiveMapper;
        this.recordingFileStore = recordingFileStore;
        this.props = props;
    }

    /**
     * 归档概览（管理端只读）：三表总量 / 超期量 / 保留天数，以及定时开关状态
     */
    public Map<String, Object> overview() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> data = new HashMap<>();
        data.put("records", tableStat(props.getRecordsRetentionDays(),
                recordMapper.selectCount(new LambdaQueryWrapper<>()),
                recordMapper.selectCount(new LambdaQueryWrapper<Record>()
                        .lt(Record::getCreatedAt, now.minusDays(props.getRecordsRetentionDays())))));
        data.put("callRecords", tableStat(props.getCallRecordsRetentionDays(),
                callRecordMapper.selectCount(new LambdaQueryWrapper<>()),
                callRecordMapper.selectCount(new LambdaQueryWrapper<CallRecord>()
                        .lt(CallRecord::getCreatedAt, now.minusDays(props.getCallRecordsRetentionDays())))));
        data.put("auditLogs", tableStat(props.getAuditLogsRetentionDays(),
                auditLogMapper.selectCount(new LambdaQueryWrapper<>()),
                auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLog>()
                        .lt(AuditLog::getCreatedAt, now.minusDays(props.getAuditLogsRetentionDays())))));
        data.put("scheduleEnabled", props.isScheduleEnabled());
        data.put("cron", props.getCron());
        return data;
    }

    private Map<String, Object> tableStat(int retentionDays, long total, long expired) {
        Map<String, Object> stat = new HashMap<>();
        stat.put("total", total);
        stat.put("expired", expired);
        stat.put("retentionDays", retentionDays);
        return stat;
    }

    /**
     * 执行归档（DB 主循环，事务内：插归档 → 物理删源表）
     * 幂等：反复调用安全；录音文件删除不在本方法内（见 {@link #deleteRecordings}）
     */
    @Transactional
    public ArchiveResult runArchive() {
        LocalDateTime now = LocalDateTime.now();
        ArchiveResult result = new ArchiveResult();
        result.setStartedAt(now);

        archiveRecords(now.minusDays(props.getRecordsRetentionDays()), result);
        archiveCallRecords(now.minusDays(props.getCallRecordsRetentionDays()), result);
        archiveAuditLogs(now.minusDays(props.getAuditLogsRetentionDays()), result);

        result.setFinishedAt(LocalDateTime.now());
        return result;
    }

    /**
     * 删除已归档 call_records 对应的录音文件（无事务，不参与 DB 回滚）。
     * 文件缺失/删除失败仅统计并记日志，不影响数据归档一致性。
     */
    public ArchiveResult deleteRecordings(ArchiveResult result) {
        int deleted = 0;
        int failed = 0;
        for (String name : result.getRecordingNames()) {
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if (recordingFileStore.delete(name)) {
                deleted++;
            } else {
                failed++;
                logger.warn("通话录音文件清理失败（已归档，可手工处理）: {}", name);
            }
        }
        result.setRecordingsDeleted(deleted);
        result.setRecordingsFailed(failed);
        result.getRecordingNames().clear();
        return result;
    }

    // ==================== 分表主循环 ====================

    private void archiveRecords(LocalDateTime cutoff, ArchiveResult result) {
        while (true) {
            // 每次取最早的 unprocessed 批次（无 OFFSET），物理删除后自然推进，避免翻页跳行
            List<Record> batch = recordMapper.selectList(new LambdaQueryWrapper<Record>()
                    .lt(Record::getCreatedAt, cutoff)
                    .orderByAsc(Record::getCreatedAt)
                    .last("LIMIT " + props.getBatchSize()));
            if (batch.isEmpty()) {
                break;
            }
            archiveMapper.archiveRecords(batch);
            archiveMapper.deleteRecordsPhysical(batch.stream().map(Record::getId).toList());
            result.setRecordsArchived(result.getRecordsArchived() + batch.size());
        }
    }

    private void archiveCallRecords(LocalDateTime cutoff, ArchiveResult result) {
        while (true) {
            List<CallRecord> batch = callRecordMapper.selectList(new LambdaQueryWrapper<CallRecord>()
                    .lt(CallRecord::getCreatedAt, cutoff)
                    .orderByAsc(CallRecord::getCreatedAt)
                    .last("LIMIT " + props.getBatchSize()));
            if (batch.isEmpty()) {
                break;
            }
            archiveMapper.archiveCallRecords(batch);
            archiveMapper.deleteCallRecordsPhysical(batch.stream().map(CallRecord::getId).toList());
            // 收集录音文件名，供事务外统一清理
            result.addRecordingNames(batch.stream()
                    .map(CallRecord::getRecordingName)
                    .filter(StringUtils::hasText)
                    .toList());
            result.setCallRecordsArchived(result.getCallRecordsArchived() + batch.size());
        }
    }

    private void archiveAuditLogs(LocalDateTime cutoff, ArchiveResult result) {
        while (true) {
            List<AuditLog> batch = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                    .lt(AuditLog::getCreatedAt, cutoff)
                    .orderByAsc(AuditLog::getCreatedAt)
                    .last("LIMIT " + props.getBatchSize()));
            if (batch.isEmpty()) {
                break;
            }
            archiveMapper.archiveAuditLogs(batch);
            archiveMapper.deleteAuditLogsPhysical(batch.stream().map(AuditLog::getId).toList());
            result.setAuditLogsArchived(result.getAuditLogsArchived() + batch.size());
        }
    }
}