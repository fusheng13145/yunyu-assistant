package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Quota;
import com.leyon.backend.entity.QuotaDailyUsage;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.mapper.AssistantMapper;
import com.leyon.backend.mapper.CallRecordMapper;
import com.leyon.backend.mapper.QuotaDailyUsageMapper;
import com.leyon.backend.mapper.QuotaMapper;
import com.leyon.backend.mapper.RecordMapper;
import com.leyon.backend.mapper.SessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用量配额服务单元测试（P2-10 用量配额与账单统计）
 * 覆盖：org 优先/user 兜底、超限拦截（助手/通话/消息）、聚合口径与默认值兜底
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotaServiceTest {

    @Mock
    private QuotaMapper quotaMapper;
    @Mock
    private QuotaDailyUsageMapper quotaDailyUsageMapper;
    @Mock
    private OrgService orgService;
    @Mock
    private AssistantMapper assistantMapper;
    @Mock
    private CallRecordMapper callRecordMapper;
    @Mock
    private RecordMapper recordMapper;
    @Mock
    private SessionMapper sessionMapper;

    private QuotaService quotaService;

    @BeforeEach
    void setUp() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Assistant.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CallRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Record.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Session.class);
        quotaService = new QuotaService(quotaMapper, quotaDailyUsageMapper, orgService,
                assistantMapper, callRecordMapper, recordMapper, sessionMapper, new QuotaPolicy());
        // 纯单测环境无 Spring 注入，手动注入默认配额值（与 application.yaml 约定一致）
        applyDefaults("defaultAssistantLimit", 50);
        applyDefaults("defaultDailyCallLimit", 20);
        applyDefaults("defaultDailyCallSecLimit", 3600L);
        applyDefaults("defaultDailyMsgLimit", 500);
        // 默认 mock：无成员记录、无配额配置、默认无数据
        lenient().when(orgService.getOrgIdOfUser(any())).thenReturn(null);
        lenient().when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        lenient().when(assistantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        lenient().when(callRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        lenient().when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        lenient().when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        lenient().when(sessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        // 原子扣减默认桩：0 行＝"当日已无余量"（各用例按需改 1 或走建行走路径）
        lenient().when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt())).thenReturn(0);
        lenient().when(quotaDailyUsageMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    }

    private void applyDefaults(String field, Object value) throws Exception {
        java.lang.reflect.Field f = QuotaService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(quotaService, value);
    }

    private Quota quota(int assistantLimit, int callLimit, long callSec, int msgLimit) {
        Quota q = new Quota();
        q.setAssistantLimit(assistantLimit);
        q.setDailyCallLimit(callLimit);
        q.setDailyCallSecLimit(callSec);
        q.setDailyMsgLimit(msgLimit);
        return q;
    }

    @Test
    void getEffective_orgPriorityOverUser() {
        when(orgService.getOrgIdOfUser("u1")).thenReturn("org1");
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 600, 50));
        Quota effective = quotaService.getEffective("u1");
        assertThat(effective.getAssistantLimit()).isEqualTo(5);
    }

    @Test
    void getEffective_userFallbackWhenNoOrg() {
        when(orgService.getOrgIdOfUser("u1")).thenReturn(null);
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(10, 10, 1200, 100));
        Quota effective = quotaService.getEffective("u1");
        assertThat(effective.getDailyCallLimit()).isEqualTo(10);
    }

    @Test
    void getDefaultQuota_exposesEnvFallbackWithoutScope() {
        Quota defaults = quotaService.getDefaultQuota();
        assertThat(defaults.getScopeType()).isNull();
        assertThat(defaults.getScopeId()).isNull();
        assertThat(defaults.getAssistantLimit()).isEqualTo(50);
        assertThat(defaults.getDailyCallLimit()).isEqualTo(20);
        assertThat(defaults.getDailyCallSecLimit()).isEqualTo(3600L);
        assertThat(defaults.getDailyMsgLimit()).isEqualTo(500);
    }

    @Test
    void checkCreateAssistant_exceededThrows() {
        when(assistantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(50L);
        assertThatThrownBy(() -> quotaService.checkCreateAssistant("u1"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void checkCreateAssistant_withinLimitPasses() {
        when(assistantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(10L);
        quotaService.checkCreateAssistant("u1");
    }

    // ===================== 上限为 0＝管理端关闭（v2.57 · C-124） =====================

    /**
     * "用满"与"被关闭"的用户动作相反（等明天 vs 找管理员），文案混用会让用户等到第二天才发现没人会恢复他。
     * 关闭判定同时必须<b>不写账本</b>：不建当日行、不推进 used，否则关闭状态会被扣减逻辑改写成"用满"。
     */
    @Test
    void zeroMsgLimitReportsDisabledAndWritesNothing() {
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 600, 0));
        assertThatThrownBy(() -> quotaService.checkSendMessage("u1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("单日消息量")
                .hasMessageContaining("关闭")
                .hasMessageNotContaining("明日再试");
        verify(quotaDailyUsageMapper, never()).updateDailyUsage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void zeroAssistantLimitReportsDisabledWithoutCountingRows() {
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(0, 5, 600, 50));
        assertThatThrownBy(() -> quotaService.checkCreateAssistant("u1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("助手数量")
                .hasMessageContaining("关闭");
    }

    /**
     * 时长上限为 0 时不得先扣通话次数：关闭语音是静态配置，不看不原子读数，
     * 排在扣减之前才不会出现"每次尝试都白烧一通次数"
     */
    @Test
    void zeroSecLimitRejectsBeforeBurningCallCount() {
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 0, 50));
        assertThatThrownBy(() -> quotaService.checkStartCall("u1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("单日通话时长")
                .hasMessageContaining("关闭");
        verify(quotaDailyUsageMapper, never()).updateDailyUsage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void exhaustedMsgLimitStillReportsTomorrow() {
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 600, 5));
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt())).thenReturn(0);
        when(quotaDailyUsageMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertThatThrownBy(() -> quotaService.checkSendMessage("u1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("单日消息量已达上限（5 条）")
                .hasMessageContaining("明日再试");
    }

    /**
     * 环境变量兜底走的是同一个边界入口：越界配置不得原样进入扣减判定
     * （否则"以为填了个不限制"的 999999 会变成账本里的 limit，而写侧根本不允许这个值）
     */
    @Test
    void envDefaultAboveBoundIsClampedBeforeConsume() throws Exception {
        applyDefaults("defaultDailyMsgLimit", 999_999);
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt())).thenReturn(1);
        quotaService.checkSendMessage("u1");
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(quotaDailyUsageMapper).updateDailyUsage(any(), any(), any(), any(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(QuotaPolicy.MAX_DAILY_MSG_LIMIT);
    }

    @Test
    void envDefaultNegativeBecomesDisabled() throws Exception {
        applyDefaults("defaultDailyCallLimit", -1);
        assertThatThrownBy(() -> quotaService.checkStartCall("u1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("单日通话次数")
                .hasMessageContaining("关闭");
    }

    @Test
    void checkSendMessage_exceededThrows() {
        // v2.35 起消息上限由"计数比较"改为原子扣减：UPDATE 影响行数 0 ⇒ 已达上限
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(0);
        assertThatThrownBy(() -> quotaService.checkSendMessage("u1"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void checkStartCall_callCountExceededThrows() {
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(0);
        assertThatThrownBy(() -> quotaService.checkStartCall("u1"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void checkStartCall_withinDurationPasses() {
        CallRecord rec = new CallRecord();
        rec.setDurationSec(120);
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(1); // 原子扣减成功
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(rec));
        // 120s < 默认 3600s：时长判定读的是当日已结算通话记录，不写扣减账本
        quotaService.checkStartCall("u1");
    }

    @Test
    void checkStartCall_durationExceededThrowsOnDurationMessage() {
        CallRecord rec = new CallRecord();
        rec.setDurationSec(3600);
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(1); // 次数额度充足，只让时长这一维越界
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(rec));
        // 断言文案含"通话时长"：否则次数分支的拒绝也能让本例通过
        assertThatThrownBy(() -> quotaService.checkStartCall("u1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("通话时长");
    }

    // ===================== 通话中时长复核（v2.59 · C-126） =====================

    /**
     * 进行中的通话在 call_records 里 durationSec=0（结算只发生在挂断时），
     * 只靠发起前那一次判定的话，一整通超长通话可以整轮穿透日上限——
     * 所以回合边界必须把"这通电话今天已经活的秒数"补进用量里再判。
     */
    @Test
    void ongoingCallSec_countsLiveCallSecondsAndThrows() {
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 600, 50));
        when(callRecordMapper.selectById("c1")).thenReturn(liveCall("c1", LocalDateTime.now().minusSeconds(700)));
        assertThatThrownBy(() -> quotaService.checkOngoingCallSec("u1", "c1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("通话时长")
                .hasMessageContaining("明日再试");
    }

    @Test
    void ongoingCallSec_settledPlusLiveStillWithinLimitPasses() {
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 600, 50));
        CallRecord settled = new CallRecord();
        settled.setDurationSec(500);
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(settled));
        when(callRecordMapper.selectById("c1")).thenReturn(liveCall("c1", LocalDateTime.now().minusSeconds(50)));
        // 500（已结算）+ 50（本通已活）< 600
        quotaService.checkOngoingCallSec("u1", "c1");
    }

    /**
     * 复核只看时长、不烧次数：回合边界每轮都会进来，
     * 若沿用 checkStartCall 会把"单日通话次数"当秒表烧掉。
     */
    @Test
    void ongoingCallSec_neverBurnsDailyCallCount() {
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 600, 50));
        when(callRecordMapper.selectById("c1")).thenReturn(liveCall("c1", LocalDateTime.now().minusSeconds(50)));
        quotaService.checkOngoingCallSec("u1", "c1");

        verify(quotaDailyUsageMapper, never()).updateDailyUsage(any(), any(), any(), any(), anyInt());
        verify(quotaDailyUsageMapper, never()).insert(any(QuotaDailyUsage.class));
    }

    @Test
    void ongoingCallSec_zeroLimitReportsDisabledNotTomorrow() {
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 0, 50));
        when(callRecordMapper.selectById("c1")).thenReturn(liveCall("c1", LocalDateTime.now().minusSeconds(50)));
        assertThatThrownBy(() -> quotaService.checkOngoingCallSec("u1", "c1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("单日通话时长")
                .hasMessageContaining("关闭");
    }

    /**
     * 跨零点口径：本通话昨日 23:00 开始，昨日那一段属于昨天的配额日，
     * 未做当日裁剪的话会在今天 00:00 之后立刻把一整夜的秒数计进今日并挂断。
     * 上限取"今日已过秒数 + 1800"：裁剪后必然放行、未裁剪（多出 3600 秒）必然拒绝。
     */
    @Test
    void ongoingCallSec_countsOnlyTodayPartOfCrossMidnightCall() {
        long sinceMidnight = java.time.Duration.between(
                LocalDate.now().atStartOfDay(), LocalDateTime.now()).getSeconds();
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(quota(5, 5, sinceMidnight + 1800, 50));
        when(callRecordMapper.selectById("c1")).thenReturn(liveCall("c1",
                LocalDate.now().atStartOfDay().minusSeconds(3600)));

        quotaService.checkOngoingCallSec("u1", "c1");
    }

    /** 通话ID 缺失（记录创建失败的降级路径）＝没有"本通已活秒数"可补，只判当日已结算量 */
    @Test
    void ongoingCallSec_withoutCallIdFallsBackToSettledOnly() {
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 600, 50));
        CallRecord settled = new CallRecord();
        settled.setDurationSec(600);
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(settled));

        assertThatThrownBy(() -> quotaService.checkOngoingCallSec("u1", null))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("通话时长");
        verify(callRecordMapper, never()).selectById(any());
    }

    private CallRecord liveCall(String id, LocalDateTime startedAt) {
        CallRecord record = new CallRecord();
        record.setId(id);
        record.setStatus(CallRecord.STATUS_IN_PROGRESS);
        record.setStartedAt(startedAt);
        record.setDurationSec(0);
        return record;
    }

    @Test
    void consumeDaily_updateHitsQuota_succeedsWithoutRowCreation() {
        when(quotaDailyUsageMapper.updateDailyUsage(eq(Quota.SCOPE_USER), eq("u1"),
                eq(QuotaDailyUsage.METRIC_DAILY_MSG), any(LocalDate.class), eq(500))).thenReturn(1);

        assertThat(quotaService.consumeDaily(Quota.SCOPE_USER, "u1",
                QuotaDailyUsage.METRIC_DAILY_MSG, 500)).isTrue();
        // 扣减成功即有余量，不应再走建行分支
        verify(quotaDailyUsageMapper, never()).insert(any(QuotaDailyUsage.class));
    }

    @Test
    void consumeDaily_updateMissesWithExistingRow_isExhausted() {
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt())).thenReturn(0);
        when(quotaDailyUsageMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        // 行已存在但 UPDATE 0 行 ⇒ used 已达 limit，且不得再试扣（会把 used 推过上限）
        assertThat(quotaService.consumeDaily(Quota.SCOPE_USER, "u1",
                QuotaDailyUsage.METRIC_DAILY_MSG, 500)).isFalse();
        verify(quotaDailyUsageMapper, times(1)).updateDailyUsage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void consumeDaily_createsRowThenRetriesUpdate() {
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(0, 1);
        when(quotaDailyUsageMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        assertThat(quotaService.consumeDaily(Quota.SCOPE_ORG, "org1",
                QuotaDailyUsage.METRIC_DAILY_CALL, 20)).isTrue();

        ArgumentCaptor<QuotaDailyUsage> captor = ArgumentCaptor.forClass(QuotaDailyUsage.class);
        verify(quotaDailyUsageMapper).insert(captor.capture());
        QuotaDailyUsage row = captor.getValue();
        assertThat(row.getScopeType()).isEqualTo(Quota.SCOPE_ORG);
        assertThat(row.getScopeId()).isEqualTo("org1");
        assertThat(row.getMetric()).isEqualTo(QuotaDailyUsage.METRIC_DAILY_CALL);
        assertThat(row.getUsageDate()).isEqualTo(LocalDate.now());
        assertThat(row.getUsed()).isEqualTo(0);
        // 建行后恰好重试一次扣减
        verify(quotaDailyUsageMapper, times(2)).updateDailyUsage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void consumeDaily_concurrentInsertConflict_isFailSafe() {
        // 本进程判"无行"，但另一实例已抢先建行 ⇒ insert 撞唯一键；重试扣减再撞窗口 ⇒ 按上限内已占处理
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt())).thenReturn(0);
        when(quotaDailyUsageMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(quotaDailyUsageMapper.insert(any(QuotaDailyUsage.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_usage_scope'"));

        assertThat(quotaService.consumeDaily(Quota.SCOPE_USER, "u1",
                QuotaDailyUsage.METRIC_DAILY_MSG, 500)).isFalse();
    }

    @Test
    void aggregateUsage_returnsQuotaCurrentRemaining() {
        when(assistantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        when(callRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        Session s = new Session();
        s.setId("s1");
        when(sessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(s));
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(40L);

        Map<String, Object> usage = quotaService.aggregateUsage("u1");
        assertThat(usage.get("period")).isEqualTo("daily");
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) usage.get("current");
        assertThat(current.get("assistantCount")).isEqualTo(3L);
        assertThat(current.get("dailyCallCount")).isEqualTo(2L);
        assertThat(current.get("dailyMsgCount")).isEqualTo(40L);
        @SuppressWarnings("unchecked")
        Map<String, Object> remaining = (Map<String, Object>) usage.get("remaining");
        assertThat(remaining.get("assistantRemaining")).isEqualTo(47L); // 默认 50 - 3
    }
}