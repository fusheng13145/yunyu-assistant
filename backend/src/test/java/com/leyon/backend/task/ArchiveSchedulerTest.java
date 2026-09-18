package com.leyon.backend.task;

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
 * 数据归档定时任务单测（P2-9）
 * 覆盖：开关关闭跳过、开启执行 runArchive + deleteRecordings
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchiveSchedulerTest {

    @Mock
    private DataArchiveService archiveService;

    private ArchiveProperties props;
    private ArchiveScheduler scheduler;

    @BeforeEach
    void setUp() {
        props = new ArchiveProperties();
        scheduler = new ArchiveScheduler(archiveService, props);
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
        ArchiveResult result = new ArchiveResult();
        when(archiveService.runArchive()).thenReturn(result);

        scheduler.scheduledArchive();

        verify(archiveService).runArchive();
        verify(archiveService).deleteRecordings(result);
        assertThat(result.getStartedAt()).isNull(); // 参数为同一实例
    }
}