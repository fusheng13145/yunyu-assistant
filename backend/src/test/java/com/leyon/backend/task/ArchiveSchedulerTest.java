package com.leyon.backend.task;

import com.leyon.backend.service.ArchiveLockService;
import com.leyon.backend.service.ArchiveProperties;
import com.leyon.backend.service.ArchiveResult;
import com.leyon.backend.service.DataArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据归档定时任务单测（P2-9；v2.19 跨实例防重）
 * 覆盖：开关关闭跳过、开启执行 runArchive + deleteRecordings、锁被占用时跳过、执行后释放锁
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchiveSchedulerTest {

    @Mock
    private DataArchiveService archiveService;
    @Mock
    private ArchiveLockService lockService;

    private ArchiveProperties props;
    private ArchiveScheduler scheduler;

    @BeforeEach
    void setUp() {
        props = new ArchiveProperties();
        scheduler = new ArchiveScheduler(archiveService, props, lockService);
    }

    @Test
    void scheduledArchive_whenDisabled_skips() {
        props.setScheduleEnabled(false);
        scheduler.scheduledArchive();
        verify(archiveService, never()).runArchive();
        verify(archiveService, never()).deleteRecordings(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void scheduledArchive_whenEnabled_runsArchiveAndDeletesRecordings() {
        props.setScheduleEnabled(true);
        when(lockService.tryAcquire()).thenReturn(true);
        ArchiveResult result = new ArchiveResult();
        when(archiveService.runArchive()).thenReturn(result);

        scheduler.scheduledArchive();

        verify(archiveService).runArchive();
        verify(archiveService).deleteRecordings(result);
        verify(lockService).release();
    }

    @Test
    void scheduledArchive_whenLockHeld_skipsAndReleasesNotCalled() {
        props.setScheduleEnabled(true);
        when(lockService.tryAcquire()).thenReturn(false);

        scheduler.scheduledArchive();

        verify(archiveService, never()).runArchive();
        verify(lockService, never()).release();
    }
}