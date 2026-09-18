package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Record;
import com.leyon.backend.mapper.ArchiveMapper;
import com.leyon.backend.mapper.AuditLogMapper;
import com.leyon.backend.mapper.CallRecordMapper;
import com.leyon.backend.mapper.RecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据归档服务单测（P2-9）
 * 覆盖：空批幂等、多批次归档流转、原 id 保留、录音文件收集、事务注解、概览统计、录音文件清理
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataArchiveServiceTest {

    @Mock
    private RecordMapper recordMapper;
    @Mock
    private CallRecordMapper callRecordMapper;
    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private ArchiveMapper archiveMapper;
    @Mock
    private RecordingFileStore recordingFileStore;

    private ArchiveProperties props;
    private DataArchiveService service;

    @BeforeEach
    void setUp() {
        // 初始化实体元数据（逻辑删除/自动填充分析需要）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Record.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CallRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditLog.class);
        props = new ArchiveProperties();
        service = new DataArchiveService(recordMapper, callRecordMapper, auditLogMapper,
                archiveMapper, recordingFileStore, props);
    }

    private Record record(String id) {
        Record r = new Record();
        r.setId(id);
        r.setAssistantId("a1");
        r.setRole(0);
        r.setMessage("m");
        r.setCreatedAt(LocalDateTime.now().minusDays(200));
        r.setIsDeleted(0);
        return r;
    }

    private CallRecord callRecord(String id, String recordingName) {
        CallRecord c = new CallRecord();
        c.setId(id);
        c.setUserId("u1");
        c.setAssistantId("a1");
        c.setStatus(CallRecord.STATUS_ENDED);
        c.setCreatedAt(LocalDateTime.now().minusDays(200));
        c.setIsDeleted(0);
        c.setRecordingName(recordingName);
        return c;
    }

    private AuditLog auditLog(String id) {
        AuditLog a = new AuditLog();
        a.setId(id);
        a.setAction("LOGIN");
        a.setResult(1);
        a.setCreatedAt(LocalDateTime.now().minusDays(200));
        return a;
    }

    @Test
    void runArchive_emptyExpired_returnsZeroAndScansAll() {
        when(recordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(auditLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ArchiveResult result = service.runArchive();

        assertThat(result.getRecordsArchived()).isZero();
        assertThat(result.getCallRecordsArchived()).isZero();
        assertThat(result.getAuditLogsArchived()).isZero();
        verify(recordMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
        verify(callRecordMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
        verify(auditLogMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
        verify(archiveMapper, never()).archiveRecords(anyList());
        verify(archiveMapper, never()).deleteRecordsPhysical(anyList());
    }

    @Test
    void runArchive_multiBatch_archivesAndDeletesPhysical() {
        // records 两批（每批最多 batchSize=500，这里用更小 batchSize 验证分批）
        props.setBatchSize(2);
        Record r1 = record("r1");
        Record r2 = record("r2");
        Record r3 = record("r3");
        when(recordMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(r1, r2), List.of(r3), List.of());
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(auditLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ArchiveResult result = service.runArchive();

        assertThat(result.getRecordsArchived()).isEqualTo(3);
        verify(archiveMapper, times(2)).archiveRecords(anyList());
        verify(archiveMapper, times(2)).deleteRecordsPhysical(anyList());
        assertThat(result.getCallRecordsArchived()).isZero();
        assertThat(result.getAuditLogsArchived()).isZero();
    }

    @Test
    void runArchive_preservesOriginalId() {
        Record r1 = record("orig-id-1");
        when(recordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(r1), List.of());
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(auditLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.runArchive();

        // 归档插入与物理删除都使用原 id（不被 ASSIGN_UUID 改写）
        verify(archiveMapper).archiveRecords(anyList());
        verify(archiveMapper).deleteRecordsPhysical(List.of("orig-id-1"));
    }

    @Test
    void runArchive_collectsRecordingNamesForDeletion() {
        CallRecord withRec = callRecord("c1", "c1.webm");
        CallRecord withoutRec = callRecord("c2", null);
        when(recordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(withRec, withoutRec), List.of());
        when(auditLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ArchiveResult result = service.runArchive();

        // 只收集有录音文件的行；deleteRecordings 统计删除成功/失败
        when(recordingFileStore.delete("c1.webm")).thenReturn(true);
        service.deleteRecordings(result);
        assertThat(result.getCallRecordsArchived()).isEqualTo(2);
        assertThat(result.getRecordingsDeleted()).isEqualTo(1);
        assertThat(result.getRecordingsFailed()).isZero();
    }

    @Test
    void runArchive_isTransactional() throws NoSuchMethodException {
        Method m = DataArchiveService.class.getMethod("runArchive");
        assertThat(m.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void overview_returnsCountsAndScheduleFlags() {
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(100L, 20L);
        when(callRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(50L, 5L);
        when(auditLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(40L, 8L);
        props.setScheduleEnabled(true);
        props.setCron("0 0 3 * * ?");

        Map<String, Object> data = service.overview();

        assertThat(((Map<?, ?>) data.get("records")).get("total")).isEqualTo(100L);
        assertThat(((Map<?, ?>) data.get("records")).get("expired")).isEqualTo(20L);
        assertThat(((Map<?, ?>) data.get("records")).get("retentionDays")).isEqualTo(180);
        assertThat(((Map<?, ?>) data.get("callRecords")).get("expired")).isEqualTo(5L);
        assertThat(((Map<?, ?>) data.get("auditLogs")).get("expired")).isEqualTo(8L);
        assertThat(data.get("scheduleEnabled")).isEqualTo(true);
        assertThat(data.get("cron")).isEqualTo("0 0 3 * * ?");
    }

    @Test
    void deleteRecordings_countsFailuresWithoutThrowing() {
        // 单个失败：文件删除返回 false（缺失/IO）时仅计数
        ArchiveResult result = new ArchiveResult();
        result.addRecordingNames(List.of("a.webm", "b.webm"));
        when(recordingFileStore.delete("a.webm")).thenReturn(true);
        when(recordingFileStore.delete("b.webm")).thenReturn(false);

        service.deleteRecordings(result);

        assertThat(result.getRecordingsDeleted()).isEqualTo(1);
        assertThat(result.getRecordingsFailed()).isEqualTo(1);
        assertThat(result.getRecordingNames()).isEmpty();
    }
}