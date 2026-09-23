package com.leyon.backend.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.entity.Quota;
import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.AssistantMapper;
import com.leyon.backend.mapper.CallRecordMapper;
import com.leyon.backend.mapper.QuotaMapper;
import com.leyon.backend.mapper.RecordMapper;
import com.leyon.backend.mapper.SessionMapper;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.service.AuditLogService;
import com.leyon.backend.service.ArchiveResult;
import com.leyon.backend.service.DataArchiveService;
import com.leyon.backend.service.QuotaService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端接口单元测试（v2.30：AdminController 此前零覆盖）
 * 覆盖：用量总览聚合、审计/用户列表的分页钳制、LIMIT 不污染 count、用户密码脱敏、
 * 归档结果映射、配额兜底展示与 UPSERT 两条分支（新建行以默认值打底 vs 已存在行字段级局部更新）
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private AssistantMapper assistantMapper;
    @Mock
    private CallRecordMapper callRecordMapper;
    @Mock
    private RecordMapper recordMapper;
    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private QuotaMapper quotaMapper;
    @Mock
    private QuotaService quotaService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private DataArchiveService dataArchiveService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        // 控制器内构造的 LambdaQueryWrapper 需要实体的列缓存，纯 Mockito 环境下手工初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Quota.class);
        controller = new AdminController(userMapper, assistantMapper, callRecordMapper, recordMapper,
                sessionMapper, quotaMapper, quotaService, auditLogService, dataArchiveService);
    }

    private Quota defaults() {
        Quota q = new Quota();
        q.setAssistantLimit(5);
        q.setDailyCallLimit(50);
        q.setDailyCallSecLimit(3000L);
        q.setDailyMsgLimit(1000);
        return q;
    }

    // ===================== 用量总览 =====================

    @Test
    void overview_aggregatesAllCounters() {
        when(userMapper.selectCount(any())).thenReturn(7L);
        when(assistantMapper.selectCount(any())).thenReturn(3L);
        when(callRecordMapper.selectCount(any())).thenReturn(120L);
        when(recordMapper.selectCount(any())).thenReturn(4000L);
        when(sessionMapper.selectCount(any())).thenReturn(88L);
        when(auditLogService.countAll()).thenReturn(233L);

        ApiResponse<Map<String, Object>> result = controller.overview();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsEntry("userCount", 7L)
                .containsEntry("assistantCount", 3L)
                .containsEntry("callRecordCount", 120L)
                .containsEntry("messageCount", 4000L)
                .containsEntry("sessionCount", 88L)
                .containsEntry("auditLogCount", 233L);
    }

    // ===================== 审计日志分页 =====================

    @Test
    void auditLogs_clampsPagingToRange() {
        when(auditLogService.pageByCreatedDesc(anyLong(), anyInt())).thenReturn(List.of());
        when(auditLogService.countAll()).thenReturn(0L);

        ApiResponse<Map<String, Object>> result = controller.auditLogs(0, 999);

        ArgumentCaptor<Long> offset = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(auditLogService).pageByCreatedDesc(offset.capture(), size.capture());
        assertThat(offset.getValue()).isZero();
        assertThat(size.getValue()).isEqualTo(50);
        assertThat(result.getData()).containsEntry("page", 1).containsEntry("pageSize", 50);
    }

    @Test
    void auditLogs_computesOffsetFromPage() {
        when(auditLogService.pageByCreatedDesc(anyLong(), anyInt())).thenReturn(List.of(new AuditLog()));
        when(auditLogService.countAll()).thenReturn(95L);

        ApiResponse<Map<String, Object>> result = controller.auditLogs(3, 20);

        verify(auditLogService).pageByCreatedDesc(40L, 20);
        assertThat(result.getData()).containsEntry("total", 95L)
                .containsEntry("page", 3).containsEntry("pageSize", 20);
        assertThat(result.getData().get("list")).asList().hasSize(1);
    }

    // ===================== 用户列表 =====================

    @Test
    void users_masksPasswords() {
        User user = new User();
        user.setId("u-1");
        user.setUsername("leyon");
        user.setPassword("$2a$10$not-a-real-hash");
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        when(userMapper.selectCount(any())).thenReturn(1L);

        ApiResponse<Map<String, Object>> result = controller.users(1, 10, null);

        assertThat(result.getData().get("list")).asList().hasSize(1);
        assertThat(user.getPassword()).isNull();
        assertThat(user.getUsername()).isEqualTo("leyon");
        assertThat(result.getData()).containsEntry("total", 1L);
    }

    /**
     * 分页 LIMIT 只能落在列表查询上：count 被 LIMIT 污染会让总数恒等于页大小（管理端表现为"用户只有 50 个"）
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void users_paginationDoesNotLeakIntoCountQuery() {
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectCount(any())).thenReturn(512L);

        controller.users(2, 10, "ad");

        ArgumentCaptor<LambdaQueryWrapper> listWrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectList(listWrapper.capture());
        ArgumentCaptor<LambdaQueryWrapper> countWrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectCount(countWrapper.capture());

        assertThat(listWrapper.getValue().getSqlSegment())
                .contains("LIMIT 10 OFFSET 10")
                .contains("LIKE");
        assertThat(countWrapper.getValue().getSqlSegment())
                .doesNotContain("LIMIT")
                .contains("LIKE");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void users_blankKeywordAddsNoCondition() {
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectCount(any())).thenReturn(0L);

        controller.users(1, 10, "   ");

        ArgumentCaptor<LambdaQueryWrapper> countWrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectCount(countWrapper.capture());
        assertThat(countWrapper.getValue().getSqlSegment()).doesNotContain("LIKE");
    }

    // ===================== 归档 =====================

    @Test
    void archiveOverview_delegatesToService() {
        when(dataArchiveService.overview()).thenReturn(Map.of("retentionDays", 180));

        ApiResponse<Map<String, Object>> result = controller.archiveOverview();

        assertThat(result.getData()).containsEntry("retentionDays", 180);
    }

    @Test
    void archiveRun_mapsCountersAndCleansRecordings() {
        ArchiveResult archiveResult = new ArchiveResult();
        archiveResult.setStartedAt(LocalDateTime.of(2026, 9, 23, 3, 0));
        archiveResult.setFinishedAt(LocalDateTime.of(2026, 9, 23, 3, 1));
        archiveResult.setRecordsArchived(11);
        archiveResult.setCallRecordsArchived(3);
        archiveResult.setAuditLogsArchived(7);
        archiveResult.setRecordingsDeleted(2);
        archiveResult.setRecordingsFailed(1);
        when(dataArchiveService.runArchive()).thenReturn(archiveResult);

        ApiResponse<Map<String, Object>> result = controller.archiveRun();

        verify(dataArchiveService).deleteRecordings(archiveResult);
        assertThat(result.getData()).containsEntry("recordsArchived", 11)
                .containsEntry("callRecordsArchived", 3)
                .containsEntry("auditLogsArchived", 7)
                .containsEntry("recordingsDeleted", 2)
                .containsEntry("recordingsFailed", 1)
                .containsEntry("startedAt", archiveResult.getStartedAt())
                .containsEntry("finishedAt", archiveResult.getFinishedAt());
    }

    // ===================== 配额展示 =====================

    @Test
    void quotas_listsConfiguredRows() {
        Quota row = defaults();
        row.setScopeType(Quota.SCOPE_ORG);
        row.setScopeId("org-1");
        when(quotaMapper.selectList(any())).thenReturn(List.of(row));

        ApiResponse<List<Quota>> result = controller.quotas();

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getScopeId()).isEqualTo("org-1");
    }

    @Test
    void quotaDefaults_exposesEnvFallback() {
        when(quotaService.getDefaultQuota()).thenReturn(defaults());

        ApiResponse<Quota> result = controller.quotaDefaults();

        assertThat(result.getData().getAssistantLimit()).isEqualTo(5);
        assertThat(result.getData().getDailyCallSecLimit()).isEqualTo(3000L);
    }

    // ===================== 配额 UPSERT =====================

    @Test
    void upsertQuota_rejectsMissingOrUnknownScope() {
        assertThat(controller.upsertQuota(null).getCode()).isEqualTo(400);

        Quota noScopeType = new Quota();
        noScopeType.setScopeId("org-1");
        assertThat(controller.upsertQuota(noScopeType).getCode()).isEqualTo(400);

        Quota badScopeType = new Quota();
        badScopeType.setScopeType("global");
        badScopeType.setScopeId("org-1");
        assertThat(controller.upsertQuota(badScopeType).getCode()).isEqualTo(400);

        verify(quotaMapper, never()).insert(any(Quota.class));
        verify(quotaMapper, never()).updateById(any(Quota.class));
    }

    /**
     * 新建行必须由默认值打底：留 NULL 的维度会在 QuotaService.getEffective 的超限比较里拆箱 NPE（C-45 同类 500）；
     * 随后那次 updateById 必须定位到刚插入的行（id 由 insert 回填，不能依赖请求体）
     */
    @Test
    void upsertQuota_newScopeSeedsEveryDimensionFromDefaults() {
        when(quotaMapper.selectOne(any())).thenReturn(null);
        when(quotaService.getDefaultQuota()).thenReturn(defaults());
        when(quotaMapper.insert(any(Quota.class))).thenAnswer(inv -> {
            inv.getArgument(0, Quota.class).setId("q-new");
            return 1;
        });

        Quota body = new Quota();
        body.setScopeType(Quota.SCOPE_USER);
        body.setScopeId("u-9");
        body.setAssistantLimit(9);

        ApiResponse<Void> result = controller.upsertQuota(body);

        ArgumentCaptor<Quota> inserted = ArgumentCaptor.forClass(Quota.class);
        verify(quotaMapper).insert(inserted.capture());
        Quota row = inserted.getValue();
        assertThat(row.getScopeType()).isEqualTo(Quota.SCOPE_USER);
        assertThat(row.getScopeId()).isEqualTo("u-9");
        assertThat(row.getAssistantLimit()).isEqualTo(5);
        assertThat(row.getDailyCallLimit()).isEqualTo(50);
        assertThat(row.getDailyCallSecLimit()).isEqualTo(3000L);
        assertThat(row.getDailyMsgLimit()).isEqualTo(1000);

        ArgumentCaptor<Quota> patch = ArgumentCaptor.forClass(Quota.class);
        verify(quotaMapper).updateById(patch.capture());
        assertThat(patch.getValue().getId()).isEqualTo("q-new");
        assertThat(patch.getValue().getAssistantLimit()).isEqualTo(9);
        assertThat(patch.getValue().getDailyCallLimit()).isNull();
        assertThat(result.getCode()).isEqualTo(200);
    }

    @Test
    void upsertQuota_existingScopeUpdatesOnlyProvidedDimensions() {
        Quota exist = new Quota();
        exist.setId("q-1");
        exist.setScopeType(Quota.SCOPE_ORG);
        exist.setScopeId("org-1");
        exist.setAssistantLimit(5);
        exist.setDailyMsgLimit(1000);
        when(quotaMapper.selectOne(any())).thenReturn(exist);

        Quota body = new Quota();
        body.setScopeType(Quota.SCOPE_ORG);
        body.setScopeId("org-1");
        body.setDailyMsgLimit(200);
        body.setDailyCallSecLimit(60L);

        ApiResponse<Void> result = controller.upsertQuota(body);

        verify(quotaMapper, never()).insert(any(Quota.class));
        ArgumentCaptor<Quota> patch = ArgumentCaptor.forClass(Quota.class);
        verify(quotaMapper).updateById(patch.capture());
        Quota update = patch.getValue();
        assertThat(update.getId()).isEqualTo("q-1");
        assertThat(update.getDailyMsgLimit()).isEqualTo(200);
        assertThat(update.getDailyCallSecLimit()).isEqualTo(60L);
        // 未提交的维度必须保持 null，交给 MP 动态 SQL 跳过，不能把已有配置覆盖成空
        assertThat(update.getAssistantLimit()).isNull();
        assertThat(update.getDailyCallLimit()).isNull();
        assertThat(update.getScopeType()).isNull();
        assertThat(result.getCode()).isEqualTo(200);
    }

    @Test
    void upsertQuota_existingScopeWithEmptyBodyChangesNoDimension() {
        Quota exist = new Quota();
        exist.setId("q-2");
        exist.setAssistantLimit(5);
        when(quotaMapper.selectOne(any())).thenReturn(exist);

        Quota body = new Quota();
        body.setScopeType(Quota.SCOPE_USER);
        body.setScopeId("u-3");

        assertThat(controller.upsertQuota(body).getCode()).isEqualTo(200);
        verify(quotaMapper, never()).insert(any(Quota.class));
        ArgumentCaptor<Quota> patch = ArgumentCaptor.forClass(Quota.class);
        verify(quotaMapper).updateById(patch.capture());
        assertThat(patch.getValue().getId()).isEqualTo("q-2");
        assertThat(patch.getValue().getAssistantLimit()).isNull();
    }
}
