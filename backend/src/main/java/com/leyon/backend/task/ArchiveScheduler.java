package com.leyon.backend.task;

import com.leyon.backend.service.ArchiveLockService;
import com.leyon.backend.service.ArchiveProperties;
import com.leyon.backend.service.ArchiveResult;
import com.leyon.backend.service.DataArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据归档定时任务（P2-9；v2.19 跨实例防重）
 * cron 恒登记，是否执行由 app.archive.schedule-enabled 开关控制（默认关闭）。
 * 与管理端手动触发复用同一 Service，无分支逻辑重复。
 * 多实例部署时经 ArchiveLockService 分布式锁确保同一时刻仅一个实例执行归档。
 *
 * @author leyon
 */
@Component
public class ArchiveScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveScheduler.class);

    private final DataArchiveService archiveService;
    private final ArchiveProperties props;
    private final ArchiveLockService lockService;

    public ArchiveScheduler(DataArchiveService archiveService, ArchiveProperties props, ArchiveLockService lockService) {
        this.archiveService = archiveService;
        this.props = props;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${app.archive.cron}")
    public void scheduledArchive() {
        if (!props.isScheduleEnabled()) {
            return;
        }
        // 跨实例防重：其他实例正在归档时本次跳过
        if (!lockService.tryAcquire()) {
            logger.info("有其他实例正在归档，本次跳过");
            return;
        }
        try {
            logger.info("定时归档开始...");
            ArchiveResult result = archiveService.runArchive();
            archiveService.deleteRecordings(result);
            logger.info("定时归档完成: records={}, callRecords={}, auditLogs={}, 录音删除={}（失败{}）",
                    result.getRecordsArchived(), result.getCallRecordsArchived(),
                    result.getAuditLogsArchived(), result.getRecordingsDeleted(), result.getRecordingsFailed());
        } finally {
            lockService.release();
        }
    }
}