package com.leyon.backend.service;

import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Org;
import com.leyon.backend.entity.OrgMember;
import com.leyon.backend.mapper.UsageStatsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用量统计口径单测（S-12 四项偏差）
 *
 * <p>覆盖：只计已结算通话、消息数取自 records 而非反列、归档行并入、组织作用域与授权、逐日补零。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UsageStatsServiceTest {

    private static final String USER = "user-1";
    private static final String OTHER = "user-2";
    private static final String ORG = "org-1";

    @Mock
    private UsageStatsMapper usageStatsMapper;

    @Mock
    private OrgService orgService;

    private UsageStatsService service;

    @BeforeEach
    void setUp() {
        service = new UsageStatsService(usageStatsMapper, orgService);
    }

    private CallRecord call(String id, String userId, Integer status, Integer durationSec, Integer messageCount,
                            LocalDateTime startedAt) {
        CallRecord record = new CallRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setStatus(status);
        record.setDurationSec(durationSec);
        record.setMessageCount(messageCount);
        record.setStartedAt(startedAt);
        return record;
    }

    private Map<String, Object> count(String callId, Object cnt) {
        Map<String, Object> row = new HashMap<>();
        row.put("callId", callId);
        row.put("cnt", cnt);
        return row;
    }

    private LocalDateTime today() {
        return LocalDate.now().atTime(10, 30);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> days(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("days");
    }

    private Map<String, Object> day(Map<String, Object> payload, String date) {
        return days(payload).stream().filter(d -> date.equals(d.get("date"))).findFirst().orElseThrow();
    }

    // ==================== 组 1：只计已结算通话 ====================

    @Test
    void onlySettledCallsAreCounted() {
        LocalDateTime now = today();
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any())).thenReturn(List.of(
                call("c-ended", USER, CallRecord.STATUS_ENDED, 60, 99, now),
                call("c-interrupted", USER, CallRecord.STATUS_INTERRUPTED, 30, 99, now),
                call("c-failed", USER, CallRecord.STATUS_FAILED, 0, 99, now),
                call("c-ongoing", USER, CallRecord.STATUS_IN_PROGRESS, 0, 99, now)));
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.countLiveMessagesByCall(anyList())).thenReturn(List.of());
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of());

        Map<String, Object> payload = service.usage(USER, "week", null);

        assertThat(payload.get("callCount")).isEqualTo(2L);
        assertThat(payload.get("totalDurationSec")).isEqualTo(90L);
        assertThat(excluded(payload)).containsEntry("failed", 1L).containsEntry("ongoing", 1L);
    }

    @Test
    void nullOrUnknownStatusIsNeitherCountedNorSilentlyDropped() {
        // 状态列是 TINYINT 且可为 NULL：不能"看着像已结束"就计入，也不能让它无声消失
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any())).thenReturn(List.of(
                call("c-null", USER, null, 500, 0, today()),
                call("c-odd", USER, 9, 500, 0, today())));
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.countLiveMessagesByCall(anyList())).thenReturn(List.of());
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of());

        Map<String, Object> payload = service.usage(USER, "week", null);

        assertThat(payload.get("callCount")).isEqualTo(0L);
        assertThat(payload.get("totalDurationSec")).isEqualTo(0L);
        assertThat(excluded(payload)).containsEntry("unknown", 2L);
    }

    @Test
    void rowWithoutStartedAtIsExcludedButNamed() {
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any())).thenReturn(List.of(
                call("c-ended", USER, CallRecord.STATUS_ENDED, 60, 1, today()),
                call("c-undated", USER, CallRecord.STATUS_ENDED, 45, 1, null)));
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.countLiveMessagesByCall(anyList())).thenReturn(List.of());
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of());

        Map<String, Object> payload = service.usage(USER, "week", null);

        assertThat(payload.get("callCount")).isEqualTo(1L);
        assertThat(payload.get("totalDurationSec")).isEqualTo(60L);
        assertThat(excluded(payload)).containsEntry("undated", 1L);
    }

    // ==================== 组 2：消息数取自 records ====================

    @Test
    void messageCountComesFromRecordsNotFromDenormalizedColumn() {
        LocalDateTime now = today();
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any()))
                .thenReturn(List.of(call("c1", USER, CallRecord.STATUS_ENDED, 60, 99, now)));
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.countLiveMessagesByCall(anyList())).thenReturn(List.of(count("c1", 3L)));
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of());

        Map<String, Object> payload = service.usage(USER, "week", null);

        assertThat(payload.get("messageCount")).isEqualTo(3L);
        assertThat(day(payload, LocalDate.now().toString()).get("messageCount")).isEqualTo(3L);
    }

    @Test
    void messageCountOfUnsettledCallsIsNotCounted() {
        LocalDateTime now = today();
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any())).thenReturn(List.of(
                call("c-settled", USER, CallRecord.STATUS_ENDED, 60, 0, now),
                call("c-ongoing", USER, CallRecord.STATUS_IN_PROGRESS, 0, 0, now)));
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.countLiveMessagesByCall(anyList()))
                .thenReturn(List.of(count("c-settled", 2L), count("c-ongoing", 7L)));
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of());

        assertThat(service.usage(USER, "week", null).get("messageCount")).isEqualTo(2L);
    }

    @Test
    void messageCountReadsCountColumnLenientlyAcrossTypes() {
        // COUNT(*) 在不同驱动/方言下可能是 Long/BigInteger/Integer，口径不该因为类型漂移成 0
        LocalDateTime now = today();
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any()))
                .thenReturn(List.of(call("c1", USER, CallRecord.STATUS_ENDED, 60, 0, now)));
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.countLiveMessagesByCall(anyList())).thenReturn(List.of(
                count("c1", java.math.BigInteger.valueOf(4)), count("c1", 2)));
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of());

        assertThat(service.usage(USER, "week", null).get("messageCount")).isEqualTo(6L);
    }

    // ==================== 组 3：归档行并入 ====================

    @Test
    void archivedCallsAndMessagesAreMergedIntoTheSameWindow() {
        LocalDateTime now = today();
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any()))
                .thenReturn(List.of(call("c-live", USER, CallRecord.STATUS_ENDED, 60, 99, now)));
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any()))
                .thenReturn(List.of(call("c-arch", USER, CallRecord.STATUS_ENDED, 30, 99, now)));
        when(usageStatsMapper.countLiveMessagesByCall(anyList())).thenReturn(List.of(count("c-live", 3L)));
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of(count("c-arch", 2L)));

        Map<String, Object> payload = service.usage(USER, "week", null);

        assertThat(payload.get("callCount")).isEqualTo(2L);
        assertThat(payload.get("totalDurationSec")).isEqualTo(90L);
        assertThat(payload.get("messageCount")).isEqualTo(5L);
        assertThat(payload.get("archivedCalls")).isEqualTo(1L);
        assertThat(day(payload, LocalDate.now().toString()).get("callCount")).isEqualTo(2L);
    }

    @Test
    void sameIdPresentInBothTablesCountsOnce() {
        LocalDateTime now = today();
        CallRecord live = call("c-dup", USER, CallRecord.STATUS_ENDED, 60, 1, now);
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any())).thenReturn(List.of(live));
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any()))
                .thenReturn(List.of(call("c-dup", USER, CallRecord.STATUS_ENDED, 60, 1, now)));
        when(usageStatsMapper.countLiveMessagesByCall(anyList())).thenReturn(List.of(count("c-dup", 2L)));
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of(count("c-dup", 2L)));

        Map<String, Object> payload = service.usage(USER, "week", null);

        assertThat(payload.get("callCount")).isEqualTo(1L);
        assertThat(payload.get("totalDurationSec")).isEqualTo(60L);
        // 消息计数按 callId 求和：两侧各报同一通话的同一批行时只认一次（活表读数优先）
        assertThat(payload.get("messageCount")).isEqualTo(2L);
        assertThat(payload.get("archivedCalls")).isEqualTo(0L);
    }

    @Test
    void archivedCallWithoutLiveTwinStillCountsMessagesFromEitherSide() {
        LocalDateTime now = today();
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any()))
                .thenReturn(List.of(call("c-arch", USER, CallRecord.STATUS_ENDED, 15, 0, now)));
        // 归档通话的消息行可能仍留在活表（两张表保留期各自独立）
        when(usageStatsMapper.countLiveMessagesByCall(anyList())).thenReturn(List.of(count("c-arch", 4L)));
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of());

        Map<String, Object> payload = service.usage(USER, "week", null);

        assertThat(payload.get("messageCount")).isEqualTo(4L);
        assertThat(payload.get("archivedCalls")).isEqualTo(1L);
    }

    // ==================== 组 4：组织作用域 ====================

    @Test
    void selfScopePassesOnlyOwnUserIdAndLabelsScope() {
        stubEmpty();
        Map<String, Object> payload = service.usage(USER, "week", null);

        assertThat(payload.get("scope")).isEqualTo("self");
        assertThat(payload).doesNotContainKey("orgId");
        assertThat(userIdsCaptured()).containsExactly(USER);
        verify(orgService, never()).listMembers(anyString(), anyString());
    }

    @Test
    void orgScopeAggregatesOverMemberUserIds() {
        stubEmpty();
        when(orgService.listMembers(ORG, USER)).thenReturn(List.of(member(OTHER), member(USER)));

        Map<String, Object> payload = service.usage(USER, "week", ORG);

        assertThat(payload.get("scope")).isEqualTo("org");
        assertThat(payload.get("orgId")).isEqualTo(ORG);
        assertThat(payload.get("memberCount")).isEqualTo(2L);
        assertThat(userIdsCaptured()).containsExactly(OTHER, USER);
    }

    @Test
    void orgScopeDeniedThrowsAndReadsNothing() {
        when(orgService.listMembers(ORG, USER)).thenThrow(new ForbiddenException("无权访问该组织资源"));

        assertThatThrownBy(() -> service.usage(USER, "week", ORG))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(usageStatsMapper);
    }

    @Test
    void blankOrgIdIsTreatedAsSelf() {
        stubEmpty();
        Map<String, Object> payload = service.usage(USER, "week", "   ");

        assertThat(payload.get("scope")).isEqualTo("self");
        verify(orgService, never()).listMembers(anyString(), anyString());
    }

    // ==================== 组 5：窗口与逐日补零 ====================

    @Test
    void rangeMapsToRollingWindowDays() {
        stubEmpty();
        assertThat(days(service.usage(USER, "day", null))).hasSize(1);
        assertThat(days(service.usage(USER, "week", null))).hasSize(7);
        assertThat(days(service.usage(USER, "month", null))).hasSize(30);
        assertThat(days(service.usage(USER, "nonsense", null))).hasSize(7);
    }

    @Test
    void windowStartIsTodayMinusNMinusOneAtStartOfDay() {
        stubEmpty();
        Map<String, Object> payload = service.usage(USER, "month", null);

        assertThat(payload.get("since")).isEqualTo(LocalDate.now().minusDays(29).atStartOfDay().toString());
        assertThat(payload.get("range")).isEqualTo("month");
    }

    @Test
    void daysAreZeroFilledAscendingAndAddUpToTotals() {
        LocalDateTime twoDaysAgo = LocalDate.now().minusDays(2).atTime(9, 0);
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any())).thenReturn(List.of(
                call("c-a", USER, CallRecord.STATUS_ENDED, 40, 0, twoDaysAgo),
                call("c-b", USER, CallRecord.STATUS_ENDED, 11, 0, today())));
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.countLiveMessagesByCall(anyList()))
                .thenReturn(List.of(count("c-a", 5L), count("c-b", 1L)));
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of());

        Map<String, Object> payload = service.usage(USER, "week", null);
        List<Map<String, Object>> series = days(payload);

        assertThat(series).hasSize(7);
        assertThat(series).extracting(d -> (String) d.get("date")).isSorted();
        assertThat(series).filteredOn(d -> ((Long) d.get("callCount")) == 0L).hasSize(5);
        assertThat(day(payload, LocalDate.now().minusDays(2).toString()).get("messageCount")).isEqualTo(5L);

        long callSum = series.stream().mapToLong(d -> ((Number) d.get("callCount")).longValue()).sum();
        long durSum = series.stream().mapToLong(d -> ((Number) d.get("durationSec")).longValue()).sum();
        long msgSum = series.stream().mapToLong(d -> ((Number) d.get("messageCount")).longValue()).sum();
        assertThat(callSum).isEqualTo(payload.get("callCount"));
        assertThat(durSum).isEqualTo(payload.get("totalDurationSec"));
        assertThat(msgSum).isEqualTo(payload.get("messageCount"));
    }

    @Test
    void emptyWindowStillReturnsZeroSeriesNotMissingKeys() {
        stubEmpty();
        Map<String, Object> payload = service.usage(USER, "week", null);

        assertThat(payload.get("callCount")).isEqualTo(0L);
        assertThat(payload.get("totalDurationSec")).isEqualTo(0L);
        assertThat(payload.get("messageCount")).isEqualTo(0L);
        assertThat(payload.get("archivedCalls")).isEqualTo(0L);
        assertThat(excluded(payload)).containsEntry("failed", 0L).containsEntry("ongoing", 0L).containsEntry("undated", 0L).containsEntry("unknown", 0L);
        assertThat(days(payload)).hasSize(7);
    }

    // ==================== 辅助 ====================

    private void stubEmpty() {
        when(usageStatsMapper.selectLiveUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.selectArchivedUsageCalls(anyList(), any())).thenReturn(List.of());
        when(usageStatsMapper.countLiveMessagesByCall(anyList())).thenReturn(List.of());
        when(usageStatsMapper.countArchivedMessagesByCall(anyList())).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> excluded(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("excludedCalls");
    }

    private OrgMember member(String userId) {
        OrgMember m = new OrgMember();
        m.setOrgId(ORG);
        m.setUserId(userId);
        m.setRole(Org.ROLE_VIEWER);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<String> userIdsCaptured() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(usageStatsMapper).selectLiveUsageCalls(captor.capture(), any());
        return captor.getValue();
    }
}
